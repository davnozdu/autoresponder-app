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
import com.davnozdu.autoresponder.rules.SimUtil
import com.davnozdu.autoresponder.rules.SkipPolicy
import kotlinx.coroutines.delay

enum class Channel { MESSAGES, MESSENGER }

/** Обработка входящих сообщений мессенджеров (RCS/WhatsApp/Telegram) через уведомления. */
object NotifResponder {

    fun handle(context: Context, sbn: StatusBarNotification, sender: String, text: String,
               channel: Channel, tag: String, isGroup: Boolean, hasReply: Boolean) {
        val app = context.applicationContext
        EventQueue.submitMsg { process(app, sbn, sender, text, channel, tag, isGroup, hasReply) }
    }

    private suspend fun process(context: Context, sbn: StatusBarNotification, sender: String,
                        text: String, channel: Channel, tag: String, isGroup: Boolean, hasReply: Boolean) {
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
        // История входящего мессенджера — ВСЕГДА (в том числе в рабочее время и для «избранных»),
        // но ровно один раз: одно сообщение WhatsApp приходит несколькими уведомлениями.
        if (channel == Channel.MESSENGER &&
            Dedup.claim("hist:$tag:${sender.trim().lowercase()}:${text.trim()}")) {
            HistoryLogger.record(context, sender, tag, "in", text)
        }
        // Ключ для лимита/анти-петли и правила по номеру (только для Messages).
        val key: String
        val number: String?
        if (channel == Channel.MESSAGES) {
            number = extractNumber(sender)
            if (number == null) { log.add("NOTIF[$tag] ${sender.take(16)} — контакт без номера, пропуск"); return }
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
                if (!Dedup.claim("crm:$key:${text.trim()}")) {
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
                if (tryRemoteInputReply(context, sbn, outText)) {
                    store0(context).markReplied(key, s.timeoutHours)
                    HistoryLogger.record(context, inId, inCh, "out", outText, auto = true)
                    NotifListenerService.dismiss(sbn.key)
                    log.add("NOTIF[$tag] $key — ответ по CRM: $outText")
                } else {
                    log.add("NOTIF[$tag] $key — ответ по CRM не ушёл (нет кнопки «Ответить»)")
                }
            }
            return
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

            val returning = store.everReplied(key)
            val reply = Responder.composeReply(context, s, text, Kind.SMS, returning, override, closedReason != null, histKey, warn)

            // Ответ через кнопку уведомления (в тот же тред: RCS/WhatsApp/Telegram).
            if (tryRemoteInputReply(context, sbn, reply)) {
                store.markReplied(key, s.timeoutHours)
                HistoryLogger.record(context, inId, inCh, "out", reply, auto = true)
                NotifListenerService.dismiss(sbn.key)
                log.add("NOTIF[$tag] $key — ответ (#${store.count(key, s.timeoutHours)}/${s.maxReplies}): $reply")
                return@withKey
            }
            // Запасной SMS только для Messages (есть номер).
            if (channel == Channel.MESSAGES && number != null) {
                val subId = SimUtil.resolveSubId(context, s.slotForNumber(number))
                val segs = SmsSender.send(context, key, reply, subId)
                if (segs >= 0) { store.markReplied(key, s.timeoutHours); HistoryLogger.record(context, key, "sms", "out", reply, auto = true); log.add("NOTIF[$tag] $key — запасной SMS ($segs сег): $reply"); return@withKey }
            }
            log.add("NOTIF[$tag] $key — ответить не удалось (нет кнопки Reply)")
        }
    }

    /** ReplyStore до основной ветки: CRM-ответ уходит раньше, чем создаётся общий store. */
    private fun store0(context: Context) = ReplyStore(context)

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

    private val placeholderRe = Regex("^\\s*\\d+\\s+(new\\s+)?messages?\\s*$", RegexOption.IGNORE_CASE)
    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || placeholderRe.matches(t) ||
            t.equals("new message", true) || t.equals("Фото", true) || t.equals("Photo", true) ||
            t.equals("Видео", true) || t.equals("Video", true) || t.equals("Sticker", true) ||
            t.equals("GIF", true) || t.equals("Voice message", true)
    }

    private fun extractNumber(sender: String): String? {
        val c = sender.trim()
        val digits = c.count { it.isDigit() }
        val looksNumber = digits >= 6 && c.all { it.isDigit() || it in "+()- " }
        return if (looksNumber) PhoneMask.normalize(c) else null
    }

    /**
     * (отправитель, текст, группа?) из уведомления.
     *
     * Текст — РОВНО последнее ВХОДЯЩЕЕ сообщение, без склейки соседних. Склейка ломала две вещи:
     *  1) дедуп SMS↔RCS (ключ «номер+текст» переставал совпадать с SMS-путём, и на одно входящее
     *     уходило два ответа), и
     *  2) промпт LLM — в «Customer's SMS» попадали и наши собственные ответы.
     * Контекст переписки берётся не отсюда, а из БД истории (см. Responder.historyBlock).
     * Свои сообщения отсеиваются по владельцу стиля (style.user), а не только по пустому
     * person: WhatsApp помечает наш ответ отправителем «You».
     */
    fun extract(n: Notification): Triple<String, String, Boolean>? {
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
            // Последнее сообщение КЛИЕНТА с текстом. Пустой текст (картинка/стикер) не должен
            // обнулять разбор — иначе уведомление молча отбрасывается ещё до журнала.
            val last = style.messages.lastOrNull { !fromSelf(it) && !it.text.isNullOrBlank() }
                ?: return null   // в уведомлении только наши сообщения — отвечать не на что
            val who = last.person?.name?.toString() ?: style.conversationTitle?.toString() ?: "?"
            val body = last.text?.toString()?.trim() ?: ""
            return Triple(who, body, style.isGroupConversation)
        }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: return null
        return Triple(title, text, false)
    }
}
