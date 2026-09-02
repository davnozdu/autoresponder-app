package com.davnozdu.autoresponder.respond

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.ReplyStore
import com.davnozdu.autoresponder.data.ReplyMode
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.LlmConfig
import com.davnozdu.autoresponder.llm.LlmFactory
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.LangDetect
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.rules.AutoReplyState
import com.davnozdu.autoresponder.rules.SkipPolicy
import com.davnozdu.autoresponder.store.AboutInfo
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.store.HistoryLogger
import com.davnozdu.autoresponder.store.PersonThreads
import com.davnozdu.autoresponder.rules.SimUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Kind { CALL, SMS }

/** Центральная логика автоответа. */
object Responder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val DEFAULT_CALL_DROP =
        "Сейчас мы не можем ответить на звонок. Напишите нам сообщение, и мы свяжемся с вами в ближайшее время."

    fun handle(context: Context, number: String?, incomingText: String?, kind: Kind, incomingSubId: Int = -1) {
        val app = context.applicationContext
        EventQueue.submit { process(app, number, incomingText, kind, incomingSubId) }
    }

    /** DEBUG: выполнить запрос моделей и записать результат/ошибку в журнал. */
    fun debugModels(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val s = Settings(app)
            val log = EventLog(app)
            try {
                val cfg = com.davnozdu.autoresponder.llm.LlmConfig(
                    s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                val list = com.davnozdu.autoresponder.llm.LlmFactory.create(cfg).listModels()
                log.add("MODELS[${s.llmProvider} ${s.llmBaseUrl}]: ${list.size} шт: ${list.take(5)}")
            } catch (e: Exception) {
                log.add("MODELS ОШИБКА: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** DEBUG: вывести в лог текущее состояние (DND/расписание/закрыто). */
    fun debugStatus(context: Context) {
        val app = context.applicationContext
        val s = Settings(app)
        val filter = ClosedState.dndFilter(app)
        val fname = when (filter) {
            1 -> "ALL(DND выкл)"; 2 -> "PRIORITY(DND вкл)"; 3 -> "NONE(тишина)"; 4 -> "ALARMS"; else -> "?($filter)"
        }
        val reason = ClosedState.reason(app, s) ?: "открыто"
        EventLog(app).add("STATUS: фильтр=$fname, closed=$reason, triggerDnd=${s.triggerOnDnd}, triggerSched=${s.triggerOnSchedule}")
    }

    /** DEBUG: прогнать конвейер С гейтингом лимита. send=false — без реальной отправки. */
    fun debugCompose(context: Context, number: String?, incomingText: String?, kind: Kind, send: Boolean) {
        val app = context.applicationContext
        scope.launch {
            val s = Settings(app)
            val log = EventLog(app)
            val norm = com.davnozdu.autoresponder.rules.PhoneMask.normalize(number) ?: "+000"
            val store = ReplyStore(app)
            if (!store.canReply(norm, s.maxReplies, s.timeoutHours)) {
                log.add("TEST[$kind] $norm — ЛИМИТ ${s.maxReplies}/таймаут ${s.timeoutHours}ч, пропуск")
                return@launch
            }
            val returning = store.everReplied(norm)
            val reply = buildReply(app, s, incomingText, kind, returning)
            val clamped = SegmentBudget.clampToBudget(applyPrefix(s.aiPrefix, reply), s.maxSegments)
            val segs = if (send) SmsSender.send(app, norm, clamped, SimUtil.resolveSubId(app, s.slotForNumber(norm)))
                       else SmsSender.segmentCount(app, clamped)
            store.markReplied(norm, s.timeoutHours)
            val mode = if (send) "SENT" else "dry"
            log.add("TEST[$kind] $norm $mode #${store.count(norm, s.timeoutHours)}/${s.maxReplies} seg=$segs len=${clamped.length}")
        }
    }

    private suspend fun process(context: Context, number: String?, incomingText: String?, kind: Kind, incomingSubId: Int = -1) {
        val s = Settings(context)
        val log = EventLog(context)
        val tag = if (kind == Kind.CALL) "CALL" else "SMS"
        val from = number ?: "?"

        if (!s.enabled) return
        if (AutoReplyState.isPaused(context)) { log.add("$tag — пауза, пропуск"); return }
        // Звонок сюда попадает только после отклонения скринингом — делаем его видимым как MISSED.
        if (kind == Kind.CALL) com.davnozdu.autoresponder.call.CallLogWriter.writeMissed(context, number)
        if (kind == Kind.CALL && !s.respondCalls) return
        if (kind == Kind.SMS && !s.respondSms) return

        if (PhoneMask.isAlphanumericSender(number)) {
            log.add("$tag $from — буквенный отправитель, пропуск"); return
        }
        val norm = PhoneMask.normalize(number) ?: run { log.add("$tag $from — нет номера, пропуск"); return }

        // Чёрный список обходит маску стран и SkipPolicy (контакт выбран явно).
        val bl = HistoryDb.get(context).blacklistMatch(norm, null)
        if (bl == null) {
            if (!PhoneMask.matches(number, s.allowedPrefixes)) {
                log.add("$tag $from — не подходит под маску стран, пропуск"); return
            }
            SkipPolicy.reason(context, number, s, kind == Kind.CALL)?.let { r ->
                log.add("$tag $from — $r, пропуск"); return
            }
        }

        // Тихий час — последним из гейтов: придерживаем только то, на что реально ответили бы.
        if (kind == Kind.CALL && QuietHours.holdIfQuiet(context, s, norm)) return

        val store = ReplyStore(context)
        // Пер-номерная блокировка: не даём событию из msg-полосы (RCS/мессенджер) и из main-полосы
        // (SMS/звонок) для ОДНОГО номера одновременно пройти canReply→…→markReplied и удвоить ответ.
        NumberLock.withKey(norm) {
            val mode = store.replyMode(norm, s.maxReplies, s.timeoutHours, s.warnEnabled)
            // «ДА» в ответ на предложение позвать мастера лимит не глушит: иначе клиент
            // уверен, что его услышали, а заявки нет — худший из возможных исходов.
            val escalating = com.davnozdu.autoresponder.crm.CrmGate.isEscalation(context, norm, incomingText)
            if (mode == ReplyMode.SILENT && !escalating) {
                log.add("$tag $norm — лимит ${s.maxReplies}, таймаут ${s.timeoutHours}ч, тишина"); return@withKey
            }

            if (bl != null) com.davnozdu.autoresponder.notif.BlacklistNotifier.record(
                context, norm, bl.name, if (kind == Kind.CALL) "call" else "sms")
            val closedReason = ClosedState.reason(context, s)
            var override: String? = null
            var fallbackText: String? = null
            var forceReply = false
            if (bl != null) {
                if (kind == Kind.CALL) {
                    if (bl.onCalls) { log.add("CALL $norm — ЧС: пропускаем звонок"); return@withKey }
                    override = bl.callPrompt?.ifBlank { null } ?: DEFAULT_CALL_DROP
                    // Без LLM отправляем этот же текст как есть, а не общий шаблон.
                    fallbackText = override
                    forceReply = true
                } else {
                    if (!bl.viaLlm || !bl.onSms) { log.add("$tag $norm — ЧС: без ответа на SMS"); return@withKey }
                    override = bl.prompt; forceReply = true
                }
            }
            // CRM: статус заказа по номеру. Отвечает и в рабочее время, если это
            // разрешено тумблером, — поэтому проверяется ДО гейта «сейчас открыто».
            val crmPhones = com.davnozdu.autoresponder.crm.CrmGate.phonesFor(context, norm, null)
            val crmReply = if (kind == Kind.SMS)
                com.davnozdu.autoresponder.crm.CrmGate.reply(
                    context, s, norm, crmPhones, incomingText, "sms", closedReason != null)
            else null
            if (crmReply != null) {
                // Дедуп тот же, что у обычного пути: одно входящее SMS приходит и
                // ресивером, и уведомлением Google Messages. Заявляем его здесь, потому
                // что до общей проверки ниже мы не доходим.
                if (kind == Kind.SMS && !Dedup.claim("sms:$norm:$incomingText")) {
                    log.add("$tag $norm — дубль, ответ по CRM пропущен"); return@withKey
                }
                if (s.replyDelayMs > 0) delay(s.replyDelayMs)
                val outText = finish(s, crmReply)
                val slotCrm = s.slotForNumber(norm)
                val segsCrm = SmsSender.send(context, norm, outText, SimUtil.resolveSubId(context, slotCrm))
                if (segsCrm >= 0) {
                    store.markReplied(norm, s.timeoutHours)
                    HistoryLogger.record(context, norm, "sms", "out", outText, auto = true)
                    log.add("$tag $norm — ответ по CRM ($segsCrm сег): $outText")
                } else {
                    log.add("$tag $norm — ОШИБКА отправки ответа по CRM")
                }
                return@withKey
            }

            if (!forceReply && closedReason == null) { log.add("$tag $from — открыто, пропуск"); return@withKey }

            if (kind == Kind.SMS && !Dedup.claim("sms:$norm:$incomingText")) {
                log.add("$tag $norm — дубль (уже обработано уведомлением), пропуск"); return@withKey
            }

            if (s.replyDelayMs > 0) delay(s.replyDelayMs)

            val warn = mode == ReplyMode.WARN
            val returning = store.everReplied(norm)
            val reply = buildReply(context, s, incomingText, kind, returning, override, closedReason != null, norm, warn, fallbackText)
            val prefixed = applyPrefix(s.aiPrefix, reply)
            val clamped = SegmentBudget.clampToBudget(prefixed, s.maxSegments)

            // Ручной режим: карту выбирает правило по префиксу номера, иначе — SIM по умолчанию.
            // Входящая SIM (incomingSubId) на этой прошивке определяется ненадёжно, поэтому
            // служит только для журнала.
            val slot = s.slotForNumber(norm)
            val subId = SimUtil.resolveSubId(context, slot)
            log.add("$tag $norm — SIM отправки: слот${slot + 1} subId=$subId " +
                    "(правило префикса; входящая subId=$incomingSubId; по умолчанию слот${s.smsSlot + 1})")
            val segs = SmsSender.send(context, norm, clamped, subId)
            if (segs >= 0) {
                store.markReplied(norm, s.timeoutHours)
                HistoryLogger.record(context, norm, if (kind == Kind.CALL) "call" else "sms", "out", clamped, auto = true)
                val label = if (warn) "предупреждение" else (closedReason ?: "ЧС")
                log.add("$tag $norm — ответ ($label, $segs сег, #${store.count(norm, s.timeoutHours)}/${s.maxReplies}): $clamped")
            } else {
                log.add("$tag $norm — ОШИБКА отправки SMS")
            }
        }
    }

    /** Готовый текст (например, шаблон CRM): префикс и обрезка как у обычного ответа. */
    fun finish(s: Settings, text: String): String =
        SegmentBudget.clampToBudget(applyPrefix(s.aiPrefix, text), s.maxSegments)

    /** Публичная сборка финального ответа (LLM/шаблон + префикс + обрезка сегментов). */
    fun composeReply(context: Context, s: Settings, incomingText: String?, kind: Kind, returning: Boolean,
                     promptOverride: String? = null, closedNow: Boolean = true,
                     historyKey: String? = null, warn: Boolean = false, fallbackText: String? = null): String {
        val reply = buildReply(context, s, incomingText, kind, returning, promptOverride, closedNow, historyKey, warn, fallbackText)
        return SegmentBudget.clampToBudget(applyPrefix(s.aiPrefix, reply), s.maxSegments)
    }

    /**
     * Почему LLM НЕ будет использована, или null — если будет.
     * Возвращаем причину строкой: без неё в журнале был необъяснимый офлайн-шаблон
     * («Сейчас нерабочее время…») вместо ответа по промпту.
     */
    private fun llmSkipReason(context: Context, s: Settings): String? {
        fun ready(prov: String, model: String, key: String) =
            model.isNotBlank() && (prov == "ollama" || key.isNotBlank())
        if (!s.llmEnabled) return "LLM выключена в настройках"
        if (!NetworkUtil.isOnline(context)) return "нет интернета"
        val ok = ready(s.llmProvider, s.llmModel, s.llmApiKey) ||
                 (s.llm2Enabled && ready(s.llm2Provider, s.llm2Model, s.llm2ApiKey))
        return if (ok) null else "не задана модель или API-ключ"
    }

    private fun buildReply(
        context: Context, s: Settings, incomingText: String?, kind: Kind, returning: Boolean,
        promptOverride: String? = null, closedNow: Boolean = true, historyKey: String? = null,
        warn: Boolean = false, fallbackText: String? = null
    ): String {
        val lang = if (kind == Kind.SMS && !incomingText.isNullOrBlank())
            LangDetect.detect(incomingText, s.defaultLang) else s.defaultLang
        val skip = llmSkipReason(context, s)
        if (skip != null) {
            EventLog(context).add("Ответ шаблоном (офлайн-заглушка): $skip")
        } else {
            try {
                // Бюджет с учётом префикса «Ответ от AI:». Для латиницы в сегмент влезает вдвое
                // больше символов, поэтому берём бюджет по языку ответа, а не всегда по UCS-2.
                val prefixLen = if (s.aiPrefix.isBlank()) 0 else s.aiPrefix.length + 1
                val budget = (SegmentBudget.charBudget(lang, s.maxSegments) - prefixLen).coerceAtLeast(40)
                val prompt = buildPrompt(context, s, incomingText, kind, budget, returning, promptOverride, closedNow, historyKey, warn)
                val out = com.davnozdu.autoresponder.llm.Llm.generate(context, prompt, budget)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM пустой ответ, шаблон")
            } catch (e: Exception) {
                EventLog(context).add("LLM ошибка: ${e.message}, шаблон")
            }
        }
        // Нет LLM / отвалилась / нет сети → заранее заданный текст (промпт звонка из чёрного
        // списка задумывался и как готовое сообщение) либо общий шаблон.
        if (!warn && !fallbackText.isNullOrBlank()) return fallbackText
        return if (warn) s.warnTemplate(lang, s.timeoutHours) else s.template(lang)
    }

    /** Добавляет пометку ИИ в начало, без двойных пробелов. */
    private fun applyPrefix(prefix: String, text: String): String {
        val p = prefix.trim()
        return if (p.isEmpty()) text else "$p $text"
    }

    /**
     * Недавняя переписка по ЧЕЛОВЕКУ из журнала (для контекста LLM).
     *
     * Берём именно ХВОСТ ветки: `thread(key, limit = 200)` сортировал по возрастанию и резал
     * LIMIT'ом, поэтому у активных номеров (600+ событий) в промпт уходила переписка
     * полугодовой давности, а свежая — никогда. Строки журнала звонков исключены: для ответа
     * они бесполезны, а окно контекста забивали целиком; вместо них — одна строка «звонил N раз».
     *
     * Ветки всех каналов одного человека склеиваются (см. [PersonThreads]): звонок, SMS,
     * WhatsApp и Telegram идут в общий контекст, отсортированный по времени.
     */
    private fun historyBlock(context: Context, key: String?, incomingText: String? = null,
                             limit: Int = 12): String {
        if (key.isNullOrBlank()) return ""
        return try {
            val db = HistoryDb.get(context)
            val keys = PersonThreads.keysFor(context, key)
            var items = db.threadTail(keys, limit, skipCalls = true)
            // Текущее входящее уже записано в историю — не дублируем его последней строкой:
            // в промпт оно и так уходит отдельным полем «Customer's SMS».
            val last = items.lastOrNull()
            if (last != null && last.direction == "in" && !incomingText.isNullOrBlank() &&
                last.body.trim() == incomingText.trim()) items = items.dropLast(1)

            val sb = StringBuilder()
            if (items.isNotEmpty()) {
                sb.append("\nPrevious conversation (oldest first, for context):\n")
                for (ev in items) {
                    sb.append(if (ev.direction == "out") "AI" else "Client")
                        .append(": ").append(ev.body.take(200)).append('\n')
                }
            }
            val calls = db.incomingCallCount(keys, System.currentTimeMillis() - 7L * 86_400_000L)
            if (calls > 0) sb.append("\nThe client also called $calls time(s) in the last 7 days.\n")
            // Видно в журнале, ушёл ли контекст в LLM и сколько его было — иначе «отвечает
            // однообразно» невозможно отличить от «контекста не нашлось».
            EventLog(context).add(
                "Контекст[$key]: ${items.size} сообщ. + звонков за 7д: $calls; " +
                "ветки: ${keys.joinToString(" | ") { it.take(24) }}")
            sb.toString()
        } catch (_: Exception) { "" }
    }

    private fun buildPrompt(
        context: Context, s: Settings, incomingText: String?, kind: Kind, budget: Int, returning: Boolean,
        promptOverride: String? = null, closedNow: Boolean = true, historyKey: String? = null, warn: Boolean = false
    ): String {
        val defName = when (s.defaultLang) { "ru" -> "Russian"; "cs" -> "Czech"; else -> "English" }
        val history = historyBlock(context, historyKey, incomingText)
        // Клиент известен, заказ у него есть, но спросил он не про статус (на прямой вопрос
        // отвечает шаблон в CrmFlow, сюда такое не доходит). Отдаём модели факты, чтобы она
        // говорила своими словами, но не выдумывала номера и сроки.
        val crm = com.davnozdu.autoresponder.crm.CrmFlow.promptBlock(
            context, com.davnozdu.autoresponder.crm.CrmGate.phonesFor(context, null, historyKey))
        // Цены — тоже факты кода, не модели: см. Prices.
        val prices = com.davnozdu.autoresponder.store.Prices.promptBlock(context, incomingText)
        // Текущие дата/время/день недели с устройства — чтобы LLM накладывала праздники на текущий год
        // и понимала «сегодня/завтра/в субботу».
        val now = java.time.LocalDateTime.now()
        val dow = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        val nowStr = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val nowBlock = "Current device date and time: $nowStr, $dow (timezone ${java.util.TimeZone.getDefault().id}). " +
            "Use it to reason about today/tomorrow/weekday and to apply holiday dates to the CURRENT year."
        // Праздники (если включены): даты, когда офис закрыт / только по договорённости.
        val holBlock = if (s.holidaysEnabled) {
            val h = com.davnozdu.autoresponder.store.Holidays.text(context)
            if (h.isBlank()) "" else
                "\nPublic holidays / days off (office closed, only by prior arrangement). " +
                "Format: MM-DD = every year (apply to the current year using the date above), YYYY-MM-DD = that exact date. " +
                "If the client asks about a specific day that is a weekend or one of these holidays, say we work only by " +
                "prior arrangement and offer the holiday booking link:\n$h\n"
        } else ""
        // Системное предупреждение (№ maxReplies+1): отдельный промпт, но с контекстом и фактами.
        if (warn) {
            val warnPrompt = s.promptWarn.replace("{hours}", s.timeoutHours.toString())
            return """
            $warnPrompt

            Facts about the business (use if relevant):
            ${AboutInfo.text(context, s.businessInfo)}
            $holBlock
            $nowBlock
            $crm
            $history
            ${if (!incomingText.isNullOrBlank()) "Customer's last message: \"$incomingText\"" else ""}
            Reply in the customer's language; if unknown, reply in $defName.
            Hard limit: at most $budget characters. One short message, no signature, no emojis, plain text only. Do NOT add any prefix yourself.
            """.trimIndent()
        }
        return if (kind == Kind.SMS && !incomingText.isNullOrBlank()) {
            val ret = if (returning) "This number has contacted us before (returning contact)."
                      else "This is a new contact (first message)."
            """
            ${promptOverride ?: s.promptSms}

            Facts about the business (use them to answer):
            ${AboutInfo.text(context, s.businessInfo)}
            $holBlock
            $nowBlock
            $crm
            $prices
            $history
            Текущий режим: ${if (closedNow) "нерабочее время (Не беспокоить включён)" else "рабочее время"}.
            Customer's SMS: "$incomingText"
            $ret
            Detect the language of the customer's message (Russian, Ukrainian, Czech, English, or any other) and reply in THAT SAME language. If you cannot determine it, reply in $defName.
            Use the content of the SMS and the previous conversation as context. Keep it brief and polite.
            Hard limit: at most $budget characters. One short message, no signature, no emojis, plain text only. Do NOT add any prefix yourself.
            """.trimIndent()
        } else {
            """
            ${promptOverride ?: s.promptCall}
            $history
            Reply in $defName.
            Hard limit: at most $budget characters. No signature, no emojis, plain text only. Do NOT add any prefix yourself.
            """.trimIndent()
        }
    }
}
