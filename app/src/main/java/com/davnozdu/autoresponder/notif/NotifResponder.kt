package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.ReplyStore
import com.davnozdu.autoresponder.data.ReplyMode
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.Dedup
import com.davnozdu.autoresponder.respond.Kind
import com.davnozdu.autoresponder.respond.Responder
import com.davnozdu.autoresponder.respond.EventQueue
import com.davnozdu.autoresponder.respond.NumberLock
import com.davnozdu.autoresponder.respond.SmsSender
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.store.HistoryLogger
import com.davnozdu.autoresponder.rules.AutoReplyState
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.rules.SenderNumber
import com.davnozdu.autoresponder.rules.SimUtil
import com.davnozdu.autoresponder.rules.SkipPolicy
import kotlinx.coroutines.delay

enum class Channel { MESSAGES, MESSENGER }

/** Обработка входящих сообщений мессенджеров (RCS/WhatsApp/Telegram) через уведомления. */
object NotifResponder {

    fun handle(context: Context, sbn: StatusBarNotification, sender: String, text: String,
               channel: Channel, tag: String, isGroup: Boolean, hasReply: Boolean,
               senderUris: List<String> = emptyList(), ts: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        EventQueue.submitMsg {
            com.davnozdu.autoresponder.respond.Handoff.bind(app, sender, tag, senderUris)
            val payload = org.json.JSONObject().put("key", sbn.key).put("sender", sender).put("text", text)
                .put("channel", channel.name).put("tag", tag).put("group", isGroup).put("ts", ts)
                .put("uris", org.json.JSONArray(senderUris))
            EventQueue.enqueue(app, com.davnozdu.autoresponder.respond.Handoff.key(app, sender, tag),
                "notification", payload, EventQueue.token("${sbn.packageName}:$sender:$ts:$text"),
                Settings(app).notifMaxAgeMin.coerceAtLeast(1) * 60_000L)
        }
    }

    internal suspend fun replay(context: Context, payload: org.json.JSONObject, jobId: Long, receivedAt: Long) {
        val sbn = NotifListenerService.current(payload.getString("key"))
        val current = sbn?.notification?.let { extract(it) }
        if (sbn == null || current == null || current.sender != payload.getString("sender") || current.text != payload.getString("text")) {
            com.davnozdu.autoresponder.store.RuntimeDb.get(context).state(jobId, "expired", "Уведомление удалено или сообщение изменилось")
            return
        }
        val uris = payload.optJSONArray("uris")
        process(context, sbn, current.sender, current.text, Channel.valueOf(payload.getString("channel")),
            payload.getString("tag"), payload.optBoolean("group"),
            sbn.notification.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true,
            if (uris == null) emptyList() else (0 until uris.length()).map { uris.getString(it) },
            payload.getLong("ts"), jobId, receivedAt)
    }

    private suspend fun process(context: Context, sbn: StatusBarNotification, sender: String,
                        text: String, channel: Channel, tag: String, isGroup: Boolean, hasReply: Boolean,
                        senderUris: List<String>, msgTs: Long, jobId: Long, receivedAt: Long) {
        val s = Settings(context)
        val log = EventLog(context)
        if (!s.enabled || !s.respondSms) return
        if (AutoReplyState.isPaused(context)) return
        if (text.isBlank() || isPlaceholder(text)) {
            log.add("NOTIF[$tag] ${sender.take(16)} — заглушка/пустой текст, пропуск"); return
        }
        // Страховка от петли: мессенджер может вернуть наш собственный ответ как входящее
        // (в WhatsApp он приходит от отправителя «You»). Наш ответ всегда начинается с префикса.
        val prefix = s.aiPrefix.trim()
        if (prefix.isNotEmpty() && text.trimStart().startsWith(prefix, ignoreCase = true)) {
            log.add("NOTIF[$tag] ${sender.take(16)} — это наш же ответ, пропуск (петля)"); return
        }
        if (isGroup) { log.add("NOTIF ${sender.take(16)} — группа, пропуск"); return }
        // WhatsApp/Telegram без кнопки ответа = канал/рассылка/не-сообщение → пропуск.
        if (channel != Channel.MESSAGES && !hasReply) {
            log.add("NOTIF[$tag] ${sender.take(16)} — нет кнопки «Ответить» в уведомлении, пропуск"); return
        }
        // Историю мессенджера пишет logHistory ещё до этих проверок — сюда доходит не всё,
        // а разговор в журнале должен быть целым (см. NotifListenerService.handlePosted).
        // Ключ для лимита/анти-петли и правила по номеру (только для Messages).
        val key: String
        val number: String?
        if (channel == Channel.MESSAGES) {
            // Номер спрашиваем у всех источников по очереди, а не только у строки отправителя:
            // по RCS клиент приходит под именем своего профиля, а сохранённый контакт — под
            // именем из книги. И в том, и в другом случае номер в уведомлении есть, просто
            // не на виду (см. SenderNumber).
            val found = SenderNumber.resolve(context, sender, senderUris, text, msgTs)
            if (found == null) { log.add("NOTIF[$tag] ${sender.take(16)} — номер не определить, пропуск"); return }
            number = found.number
            if (found.source != "отправитель")
                log.add("NOTIF[$tag] ${sender.take(16)} -> $number (источник: ${found.source})")
            key = PhoneMask.normalize(number)!!
        } else {
            number = null
            // Если отправитель показан телефоном (Telegram так делает без имени/@username),
            // приводим его к цифрам: иначе «+31 6 1234 5678» и «+31612345678» считались бы
            // разными собеседниками и лимит ответов для каждого шёл бы отдельно.
            val ident = sender.trim()
            key = if (PhoneMask.looksLikeNumber(ident))
                "$tag:${ident.filter { it.isDigit() }.takeLast(9)}"
            else "$tag:${ident.lowercase()}"
        }

        // Чёрный список ищем ДО фильтров и так же, как на пути звонков и SMS: контакт выбран
        // вручную, поэтому маска стран и «Избранные» на него не распространяются. Раньше порядок
        // был обратным, и человек, оказавшийся и в «Избранных» (или просто звёздный контакт), и в
        // ЧС, в мессенджере молча пропускался, а в SMS обрабатывался как ЧС.
        val bl = HistoryDb.get(context).blacklistMatch(number, sender)
        if (bl == null) {
            if (channel == Channel.MESSAGES) {
                if (!PhoneMask.matches(number, s.allowedPrefixes)) {
                    log.add("NOTIF[$tag] $number — не под маску, пропуск"); return
                }
                SkipPolicy.reason(context, number, s, isCall = false)?.let {
                    log.add("NOTIF[$tag] $number — $it, пропуск"); return
                }
            } else {
                // Тот же список «Избранных», что и на пути звонков и SMS: имя, номер или маска.
                // Плюс общие переключатели «звёздные / все контакты» — контакт ищется по ИМЕНИ,
                // потому что WhatsApp и Telegram кладут в уведомление имя из книги, а не номер.
                SkipPolicy.reasonForSender(context, sender, s)?.let {
                    log.add("NOTIF[$tag] ${sender.take(16)} — $it, пропуск"); return
                }
            }
        }

        // Сообщение прошло чёрный список, маску и «Избранных» — робот берёт его на себя.
        // Счётчик живого уведомления DND тикает только отсюда: раньше он считал всё, что
        // попадало в журнал, то есть и переписку с роднёй, к которой автоответчик не имеет
        // отношения.
        DndStats.onIncoming(context, isCall = false)

        val inCh = if (channel == Channel.MESSAGES) "rcs" else tag
        val inId = if (channel == Channel.MESSAGES) number else sender

        // Чёрный список отвечает ВСЕГДА; обычные — только когда «закрыто».
        if (bl != null) BlacklistNotifier.record(context, number, sender, tag)
        val closedReason = ClosedState.reason(context, s)
        if (bl != null) {
            val chOk = if (channel == Channel.MESSAGES) bl.onSms else bl.onMsgr
            if (!bl.viaLlm || !chOk) { log.add("NOTIF[$tag] $key — ЧС: без ответа"); return }
        }
        val forceReply = bl != null

        // CRM: статус заказа. Номера в уведомлении мессенджера нет — он берётся из
        // телефонной книги по имени отправителя (контакта нет — в CRM не идём).
        // Проверяется ДО гейта «сейчас открыто»: отвечать статусом днём разрешает
        // отдельный тумблер.
        val histKeyEarly = if (channel == Channel.MESSAGES) key else sender.trim()
        val crmPhones = com.davnozdu.autoresponder.crm.CrmGate.phonesFor(
            context, number, if (channel == Channel.MESSAGES) null else sender)
        // Считаем ДО обращения к CrmGate: обработав «ДА», он состояние разговора стирает,
        // и после вызова отличить эскалацию от обычного вопроса уже нельзя.
        val crmEscalating = com.davnozdu.autoresponder.crm.CrmGate.isEscalation(context, histKeyEarly, text)
        val crmReply = com.davnozdu.autoresponder.crm.CrmGate.reply(
            context, s, histKeyEarly, crmPhones, text, inCh, closedReason != null)
        if (crmReply != null) {
            NumberLock.withKey(key) {
                // RCS и SMS Google Messages могут доставить одно сообщение двумя путями;
                // CRM должен использовать тот же namespace, что и SmsReceiver.
                val crmDedupKey = if (channel == Channel.MESSAGES)
                    "sms:$key:${text.trim()}"
                else
                    "crm:$key:${text.trim()}"
                if (!Dedup.claim(crmDedupKey)) {
                    log.add("NOTIF[$tag] $key — дубль, ответ по CRM пропущен"); return@withKey
                }
                // Лимит действует и на ответы по CRM — иначе одинаковые «ну что там?»
                // получали бы ответ бесконечно. Но «ДА» он не глушит: клиент уверен,
                // что его услышали, а заявки нет — худший из возможных исходов.
                if (!crmEscalating &&
                    store0(context).replyMode(key, s.maxReplies, s.timeoutHours, s.warnEnabled) == ReplyMode.SILENT) {
                    log.add("NOTIF[$tag] $key — лимит/таймаут, ответ по CRM пропущен"); return@withKey
                }
                val outText = Responder.finish(s, crmReply)
                if (EventQueue.beforeSend(context, jobId) && tryRemoteInputReply(context, sbn, outText)) {
                    store0(context).markReplied(key, s.timeoutHours)
                    recordOut(context, inId, inCh, outText)
                    DndStats.onAutoReply(context)
                    NotifListenerService.dismiss(sbn.key)
                    log.add("NOTIF[$tag] $key — ответ по CRM: $outText")
                } else {
                    log.add("NOTIF[$tag] $key — ответ по CRM не ушёл (нет кнопки «Ответить»)")
                }
            }
            return
        }

        if (com.davnozdu.autoresponder.respond.Handoff.blocked(context, histKeyEarly, inCh, receivedAt)) {
            log.add("NOTIF[$tag] — отвечает владелец, автоответ отменён"); return
        }
        if (!forceReply && closedReason == null) {
            log.add("NOTIF[$tag] ${sender.take(16)} — сейчас открыто (не DND/не расписание), пропуск"); return
        }
        val override = if (forceReply) bl.prompt else null

        val store = ReplyStore(context)
        // Пер-адресатная блокировка (общая с main-полосой по нормализованному номеру):
        // не даём RCS/мессенджеру и SMS/звонку одновременно превысить лимит по одному номеру.
        NumberLock.withKey(key) {
            // Для Messages пауза: обычное SMS застолбит SmsReceiver, до сюда дойдёт только RCS.
            if (channel == Channel.MESSAGES) delay(2000)
            // Дедуп. Для RCS(MESSAGES) — по номеру+тексту (совпадает с SMS-путём, гасит дубль SMS↔RCS).
            // Для мессенджеров — по ОТПРАВИТЕЛЬ+ТЕКСТ (не по sbn.key!): WhatsApp постит одно сообщение
            // НЕСКОЛЬКИМИ уведомлениями с разными tag/key (напр. tag=hash и tag=null) и повторно обновляет
            // одно и то же — на одно сообщение отвечаем РОВНО раз.
            val dedupKey = if (channel == Channel.MESSAGES) "sms:$key:$text"
                           else "$tag:$key:${text.trim()}"
            if (!Dedup.claim(dedupKey)) { log.add("NOTIF[$tag] $key — дубль/повтор уведомления, пропуск"); return@withKey }

            // История входящего — после дедупа (чтобы не дублировать SMS и повторные уведомления
            // мессенджеров), но ДО гейта лимита: во время таймаута клиент пишет дальше —
            // сообщения копим для контекста LLM. Группы не пишем.
            if (channel == Channel.MESSAGES) HistoryLogger.record(context, number, "rcs", "in", text)

            val mode = store.replyMode(key, s.maxReplies, s.timeoutHours, s.warnEnabled)
            if (mode == ReplyMode.SILENT) { log.add("NOTIF[$tag] $key — лимит/таймаут, копим для контекста"); return@withKey }
            val warn = mode == ReplyMode.WARN
            val histKey = if (channel == Channel.MESSAGES) key else sender.trim()

            HandoffNotifications.show(context, histKeyEarly, inCh)
            val returning = store.everReplied(key)
            val reply = Responder.composeReply(context, s, text, Kind.SMS, returning, override, closedReason != null, histKey, warn)

            // Слово в слово то же самое, что уже ушло минуту назад, второй раз не отправляем.
            //
            // Анти-дубль выше сверяет ВХОДЯЩИЙ текст, и этого не хватает: WhatsApp обновляет
            // одно уведомление по мере того, как клиент досылает фото («1 photo» → «2 photos»),
            // а бывает и что на одну реплику приходит два уведомления подряд. Клиент в итоге
            // получал один и тот же ответ дважды-трижды. Сверка по ИСХОДЯЩЕМУ тексту закрывает
            // это независимо от того, чем различались входящие.
            if (justSent(context, inId, inCh, reply)) {
                log.add("NOTIF[$tag] $key — такой же ответ уже отправлен, пропуск"); return@withKey
            }
            // Ответ через кнопку уведомления (в тот же тред: RCS/WhatsApp/Telegram).
            if (!Settings(context).enabled || AutoReplyState.isPaused(context) ||
                com.davnozdu.autoresponder.respond.Handoff.blocked(context, histKeyEarly, inCh, receivedAt)) {
                log.add("NOTIF[$tag] — пауза во время подготовки, ответ отменён"); return@withKey
            }
            if (!EventQueue.beforeSend(context, jobId)) return@withKey
            val transportId = com.davnozdu.autoresponder.respond.Outgoing.startRemote(context, histKeyEarly, inCh, reply, jobId)
            if (tryRemoteInputReply(context, sbn, reply)) {
                com.davnozdu.autoresponder.respond.Outgoing.remoteResult(context, transportId, true)
                store.markReplied(key, s.timeoutHours)
                recordOut(context, inId, inCh, reply)
                DndStats.onAutoReply(context)
                NotifListenerService.dismiss(sbn.key)
                log.add("NOTIF[$tag] $key — ответ (#${store.count(key, s.timeoutHours)}/${s.maxReplies}): $reply")
                return@withKey
            }
            com.davnozdu.autoresponder.respond.Outgoing.remoteResult(context, transportId, false)
            // Запасной SMS только для Messages (есть номер).
            if (channel == Channel.MESSAGES && number != null) {
                val subId = SimUtil.resolveSubId(context, s.slotForNumber(number))
                val segs = SmsSender.send(context, key, reply, subId, jobId = jobId, historyChannel = "sms", limitKey = key, timeoutHours = s.timeoutHours)
                if (segs >= 0) { log.add("NOTIF[$tag] $key — запасной SMS ($segs сег): $reply"); return@withKey }
            }
            log.add("NOTIF[$tag] $key — ответить не удалось (нет кнопки Reply)")
        }
    }

    /** ReplyStore до основной ветки: CRM-ответ уходит раньше, чем создаётся общий store. */
    private fun store0(context: Context) = ReplyStore(context)

    /**
     * Насколько назад считаем ответ «только что отправленным».
     *
     * Наблюдавшиеся дубли укладывались в секунды — мессенджер обновляет уведомление, пока
     * клиент досылает файлы. Окно взято с запасом, но не шире: если через полчаса клиент
     * спросит снова и модель ответит теми же словами, ответ ему всё-таки нужен.
     */
    private const val REPEAT_WINDOW_MS = 5 * 60_000L

    /**
     * Такой же ответ этому же человеку уже ушёл в пределах [REPEAT_WINDOW_MS]?
     *
     * Смотрим в журнал, а не в память процесса: он переживает перезапуск, и ветки всех
     * каналов одного человека там уже склеены (см. PersonThreads).
     */
    private fun justSent(context: Context, inId: String?, channel: String, text: String): Boolean =
        try {
            val keys = com.davnozdu.autoresponder.store.PersonThreads.keysFor(context, inId)
            keys.isNotEmpty() && HistoryDb.get(context).existsNear(
                keys, channel, "out", text, System.currentTimeMillis(), REPEAT_WINDOW_MS)
        } catch (_: Exception) { false }

    /**
     * Записать наш ответ в журнал один раз.
     *
     * Мессенджер возвращает наш же ответ обратно в уведомлении, и оттуда его подбирает
     * [logHistory] — в журнале появлялась вторая копия. Дедуп в HistoryLogger её не ловил:
     * для мессенджеров он сверяется только с ЧУЖИМИ ключами, а тут ключ тот же самый.
     * Столбим текст тем же ключом, которым пользуется [logHistory], — эхо не пройдёт.
     */
    private fun recordOut(context: Context, inId: String?, channel: String, text: String) {
        Dedup.claim("histout:$channel:${inId?.trim()?.lowercase()}:${text.trim()}")
        HistoryLogger.record(context, inId, channel, "out", text, auto = true)
    }

    private fun tryRemoteInputReply(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        return try {
            val n = sbn.notification ?: return false
            val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: Exception) {
            EventLog(context).add("NOTIF remoteInput error: ${e.message}"); false
        }
    }

    /**
     * Отвечать не на что: вложение без подписи, счётчик непрочитанных или реакция.
     * Правила и причины — в [NotifText], там же тесты.
     */
    private fun isPlaceholder(text: String): Boolean = NotifText.isNotAMessage(text)

    /** Разобранное уведомление мессенджера. */
    data class Extracted(
        val sender: String,
        /** Последнее ВХОДЯЩЕЕ сообщение и его время. */
        val text: String,
        val ts: Long,
        val isGroup: Boolean,
        /** Наши собственные сообщения из этого же уведомления (время, текст). */
        val ours: List<Pair<Long, String>> = emptyList(),
        /**
         * Ссылки на собеседника из уведомления: `Person.uri` и список `EXTRA_PEOPLE`.
         *
         * Там лежит `tel:<номер>` для незнакомца и ссылка на карточку книги для
         * сохранённого контакта — единственное место, где номер есть, когда в заголовке
         * уведомления стоит имя (см. [com.davnozdu.autoresponder.rules.SenderNumber]).
         */
        val senderUris: List<String> = emptyList()
    )

    /**
     * Разбор уведомления мессенджера.
     *
     * Текст — РОВНО последнее ВХОДЯЩЕЕ сообщение, без склейки соседних. Склейка ломала две вещи:
     *  1) дедуп SMS↔RCS (ключ «номер+текст» переставал совпадать с SMS-путём, и на одно входящее
     *     уходило два ответа), и
     *  2) промпт LLM — в «Customer's SMS» попадали и наши собственные ответы.
     * Контекст переписки берётся не отсюда, а из БД истории (см. Responder.historyBlock).
     *
     * Свои сообщения по-прежнему не путаются с клиентскими (WhatsApp помечает наш ответ
     * отправителем «You», а не пустым person), но и НЕ выбрасываются: они уходят в [ours] и
     * попадают в журнал как исходящие. Раньше их отбрасывали совсем, и в контексте оставался
     * односторонний монолог клиента — робот отвечал так, будто разговора не было.
     */
    fun extract(n: Notification): Extracted? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            // Кто «мы» в этом чате. WhatsApp дописывает наш собственный ответ обратно в
            // уведомление и помечает его отправителем («You»), а не пустым person, как
            // предполагает стандарт. Без сравнения с владельцем стиля мы принимали свой же
            // ответ за сообщение клиента и отвечали на него по кругу.
            val self = style.user?.name?.toString()?.trim()
            fun fromSelf(m: NotificationCompat.MessagingStyle.Message): Boolean {
                val who = m.person?.name?.toString()?.trim() ?: return true  // null = «от себя»
                return !self.isNullOrBlank() && who.equals(self, ignoreCase = true)
            }
            val ours = style.messages
                .filter { fromSelf(it) }
                .mapNotNull { m ->
                    val t = m.text?.toString()?.trim().orEmpty()
                    if (t.isEmpty()) null else m.timestamp to t
                }
            // Последнее сообщение КЛИЕНТА с текстом. Пустой текст (картинка/стикер) не должен
            // обнулять разбор — иначе уведомление молча отбрасывается ещё до журнала.
            val last = style.messages.lastOrNull { !fromSelf(it) && !it.text.isNullOrBlank() }
            val who = last?.person?.name?.toString()
                ?: style.conversationTitle?.toString()
                ?: return null
            // Уведомление только с нашими сообщениями отвечать не на что, но записать в
            // журнал есть что — поэтому возвращаем его с пустым текстом, а не null.
            return Extracted(
                sender = who,
                text = last?.text?.toString()?.trim() ?: "",
                ts = last?.timestamp?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                isGroup = style.isGroupConversation,
                ours = ours + remoteInputHistory(n),
                senderUris = listOfNotNull(last?.person?.uri) + peopleUris(n)
            )
        }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: return null
        return Extracted(title, text, System.currentTimeMillis(), false,
            remoteInputHistory(n), peopleUris(n))
    }

    /**
     * Ссылки на собеседников из общих полей уведомления.
     *
     * `EXTRA_PEOPLE_LIST` хранит `Person`, устаревший `EXTRA_PEOPLE` — те же ссылки строками;
     * разные прошивки заполняют разное, поэтому берём оба. Это запасной источник номера, и
     * стоит он дёшево: уведомление уже в руках.
     */
    @Suppress("DEPRECATION")
    private fun peopleUris(n: Notification): List<String> {
        val out = ArrayList<String>()
        try {
            n.extras.getParcelableArray(Notification.EXTRA_PEOPLE_LIST)
                ?.forEach { p -> (p as? android.app.Person)?.uri?.let { out.add(it) } }
            n.extras.getStringArray(Notification.EXTRA_PEOPLE)?.forEach { out.add(it) }
        } catch (_: Exception) { /* чужие поля — не повод падать */ }
        return out
    }

    /**
     * Ответы, отправленные ПРЯМО ИЗ ШТОРКИ — нами или владельцем.
     *
     * Раньше непустая история удалённого ввода означала «уже отвечено, уведомление пропустить»,
     * и текст ответа терялся. Для решения «отвечать ли» она по-прежнему стоп-сигнал, но сам
     * ответ — часть разговора и должен быть в журнале.
     *
     * Своей отметки времени у этих строк нет; ставим «сейчас» — они всегда самые свежие
     * в уведомлении.
     */
    private fun remoteInputHistory(n: Notification): List<Pair<Long, String>> {
        val hist = n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            ?: return emptyList()
        val now = System.currentTimeMillis()
        return hist.mapNotNull { it?.toString()?.trim()?.ifEmpty { null } }.map { now to it }
    }

    /**
     * Запись разговора в журнал — ДО всех правил ответа.
     *
     * Раньше история писалась внутри [process], то есть после цепочки ранних выходов: старое
     * уведомление, нет кнопки «Ответить», робот выключен или на паузе, «Фото» вместо текста.
     * В каждом из этих случаев сообщение не попадало в журнал вообще, и в контексте
     * следующего ответа зияла дыра. Записывать нужно всегда — решать, отвечать ли, отдельно.
     */
    fun logHistory(context: Context, ex: Extracted, tag: String) {
        if (ex.isGroup) return
        val who = ex.sender.trim()
        if (who.isEmpty() || isServiceNotification(who, ex.text)) return
        val app = context.applicationContext
        // В журнале — только текст. Вложение («Фото», «Voice message») текстом не является:
        // ни ответить по нему, ни понять из него что-либо LLM не может. Счётчик непрочитанных
        // («3 new messages») — тем более: содержания в нём нет, а ветку он засоряет.
        val body = if (isPlaceholder(ex.text)) "" else ex.text
        if (body.isNotEmpty() &&
            Dedup.claim("hist:$tag:${who.lowercase()}:${body.trim()}")) {
            HistoryLogger.record(app, who, tag, "in", body, ts = ex.ts)
        }
        // Наш собственный ответ возвращается в уведомление как «отвечено из шторки», и по виду
        // он неотличим от ответа владельца. Различаем по префиксу ИИ: без этого экран
        // «Требуют ответа» считал бы авто-ответ живым ответом и убирал ветку из списка —
        // то есть ровно то обещание «ответим в рабочее время» осталось бы невыполненным.
        val prefix = Settings(app).aiPrefix.trim()
        for ((ts, text) in ex.ours) {
            if (text.isBlank()) continue
            if (!Dedup.claim("histout:$tag:${who.lowercase()}:${text.trim()}")) continue
            val auto = prefix.isNotEmpty() && text.trimStart().startsWith(prefix, ignoreCase = true)
            HistoryLogger.record(app, who, tag, "out", text, auto = auto,
                ts = ts.takeIf { it > 0 } ?: ex.ts)
        }
    }

    /**
     * Уведомление мессенджера, которое сообщением НЕ является.
     *
     * WhatsApp постит под видом чата свои служебные строки: ход резервного копирования,
     * «содержимое скрыто» на заблокированном экране, пропущенный звонок. Пропущенный звонок
     * доходил до ответа — клиент получал «Сейчас нерабочее время» на то, что вообще не писал.
     */
    fun isServiceNotification(sender: String, text: String): Boolean {
        val s = sender.trim().lowercase()
        val t = text.trim().lowercase()
        if (s.startsWith("backup ") || s.startsWith("резервное копирование")) return true
        val junk = listOf(
            "missed voice call", "missed video call", "пропущенный звонок",
            "sensitive notification content hidden", "содержимое уведомления скрыто",
            "checking for new messages", "preparing backup", "backup in progress"
        )
        return junk.any { s == it || t == it || t.startsWith(it) }
    }
}
