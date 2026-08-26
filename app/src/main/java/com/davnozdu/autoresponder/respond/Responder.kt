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
            val returning = store.count(norm) > 0
            val reply = buildReply(app, s, incomingText, kind, returning)
            val clamped = SegmentBudget.clampToBudget(applyPrefix(s.aiPrefix, reply), s.maxSegments)
            val segs = if (send) SmsSender.send(app, norm, clamped, SimUtil.resolveSubId(app, s.smsSlot)) else SmsSender.segmentCount(app, clamped)
            store.markReplied(norm, s.timeoutHours)
            val mode = if (send) "SENT" else "dry"
            log.add("TEST[$kind] $norm $mode #${store.count(norm)}/${s.maxReplies} seg=$segs len=${clamped.length}")
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

        val store = ReplyStore(context)
        // Пер-номерная блокировка: не даём событию из msg-полосы (RCS/мессенджер) и из main-полосы
        // (SMS/звонок) для ОДНОГО номера одновременно пройти canReply→…→markReplied и удвоить ответ.
        NumberLock.withKey(norm) {
            val mode = store.replyMode(norm, s.maxReplies, s.timeoutHours, s.warnEnabled)
            if (mode == ReplyMode.SILENT) {
                log.add("$tag $norm — лимит ${s.maxReplies}, таймаут ${s.timeoutHours}ч, тишина"); return@withKey
            }

            if (bl != null) com.davnozdu.autoresponder.notif.BlacklistNotifier.record(
                context, norm, bl.name, if (kind == Kind.CALL) "call" else "sms")
            val closedReason = ClosedState.reason(context, s)
            var override: String? = null
            var forceReply = false
            if (bl != null) {
                if (kind == Kind.CALL) {
                    if (bl.onCalls) { log.add("CALL $norm — ЧС: пропускаем звонок"); return@withKey }
                    override = bl.callPrompt?.ifBlank { null } ?: DEFAULT_CALL_DROP
                    forceReply = true
                } else {
                    if (!bl.viaLlm || !bl.onSms) { log.add("$tag $norm — ЧС: без ответа на SMS"); return@withKey }
                    override = bl.prompt; forceReply = true
                }
            }
            if (!forceReply && closedReason == null) { log.add("$tag $from — открыто, пропуск"); return@withKey }

            if (kind == Kind.SMS && !Dedup.claim("sms:$norm:$incomingText")) {
                log.add("$tag $norm — дубль (уже обработано уведомлением), пропуск"); return@withKey
            }

            if (s.replyDelayMs > 0) delay(s.replyDelayMs)

            val warn = mode == ReplyMode.WARN
            val returning = store.count(norm) > 0
            val reply = buildReply(context, s, incomingText, kind, returning, override, closedReason != null, norm, warn)
            val prefixed = applyPrefix(s.aiPrefix, reply)
            val clamped = SegmentBudget.clampToBudget(prefixed, s.maxSegments)

            // Железно: отвечаем SMS с ТОЙ SIM, на которую пришёл вызов/SMS (incomingSubId).
            // Только если она неизвестна — берём явно выбранный слот, иначе системную.
            val subId = when {
                incomingSubId >= 0 -> incomingSubId
                s.smsSlot >= 0 -> SimUtil.resolveSubId(context, s.smsSlot)
                else -> -1
            }
            log.add("$tag $norm — SIM отправки subId=$subId (входящая=$incomingSubId, слот=${s.smsSlot})")
            val segs = SmsSender.send(context, norm, clamped, subId)
            if (segs >= 0) {
                store.markReplied(norm, s.timeoutHours)
                HistoryLogger.record(context, norm, if (kind == Kind.CALL) "call" else "sms", "out", clamped, auto = true)
                val label = if (warn) "предупреждение" else (closedReason ?: "ЧС")
                log.add("$tag $norm — ответ ($label, $segs сег, #${store.count(norm)}/${s.maxReplies}): $clamped")
            } else {
                log.add("$tag $norm — ОШИБКА отправки SMS")
            }
        }
    }

    /** Публичная сборка финального ответа (LLM/шаблон + префикс + обрезка сегментов). */
    fun composeReply(context: Context, s: Settings, incomingText: String?, kind: Kind, returning: Boolean,
                     promptOverride: String? = null, closedNow: Boolean = true,
                     historyKey: String? = null, warn: Boolean = false): String {
        val reply = buildReply(context, s, incomingText, kind, returning, promptOverride, closedNow, historyKey, warn)
        return SegmentBudget.clampToBudget(applyPrefix(s.aiPrefix, reply), s.maxSegments)
    }

    /** LLM активна только при включённом тумблере, онлайн и готовом провайдере (модель + ключ, кроме ollama). */
    private fun llmUsable(context: Context, s: Settings): Boolean {
        fun ready(prov: String, model: String, key: String) =
            model.isNotBlank() && (prov == "ollama" || key.isNotBlank())
        if (!s.llmEnabled || !NetworkUtil.isOnline(context)) return false
        return ready(s.llmProvider, s.llmModel, s.llmApiKey) ||
               (s.llm2Enabled && ready(s.llm2Provider, s.llm2Model, s.llm2ApiKey))
    }

    private fun buildReply(
        context: Context, s: Settings, incomingText: String?, kind: Kind, returning: Boolean,
        promptOverride: String? = null, closedNow: Boolean = true, historyKey: String? = null, warn: Boolean = false
    ): String {
        val lang = if (kind == Kind.SMS && !incomingText.isNullOrBlank())
            LangDetect.detect(incomingText, s.defaultLang) else s.defaultLang
        if (llmUsable(context, s)) {
            try {
                // Бюджет с учётом префикса «Ответ от AI:» (консервативно как UCS-2).
                val prefixLen = if (s.aiPrefix.isBlank()) 0 else s.aiPrefix.length + 1
                val budget = (SegmentBudget.charBudget("ru", s.maxSegments) - prefixLen).coerceAtLeast(40)
                val prompt = buildPrompt(context, s, incomingText, kind, budget, returning, promptOverride, closedNow, historyKey, warn)
                val out = com.davnozdu.autoresponder.llm.Llm.generate(context, prompt, budget)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM пустой ответ, шаблон")
            } catch (e: Exception) {
                EventLog(context).add("LLM ошибка: ${e.message}, шаблон")
            }
        }
        // Нет LLM / отвалилась / нет сети → шаблон (для warn — предупреждающий шаблон).
        return if (warn) s.warnTemplate(lang, s.timeoutHours) else s.template(lang)
    }

    /** Добавляет пометку ИИ в начало, без двойных пробелов. */
    private fun applyPrefix(prefix: String, text: String): String {
        val p = prefix.trim()
        return if (p.isEmpty()) text else "$p $text"
    }

    /** Недавняя переписка по адресату из журнала (для контекста LLM). */
    private fun historyBlock(context: Context, key: String?, limit: Int = 8): String {
        if (key.isNullOrBlank()) return ""
        return try {
            val items = HistoryDb.get(context).thread(key, limit = 200)
            if (items.isEmpty()) return ""
            val lines = items.takeLast(limit).joinToString("\n") {
                val who = if (it.direction == "out") "AI" else "Client"
                "$who: ${it.body.take(200)}"
            }
            "\nPrevious conversation (oldest first, for context):\n$lines\n"
        } catch (_: Exception) { "" }
    }

    private fun buildPrompt(
        context: Context, s: Settings, incomingText: String?, kind: Kind, budget: Int, returning: Boolean,
        promptOverride: String? = null, closedNow: Boolean = true, historyKey: String? = null, warn: Boolean = false
    ): String {
        val defName = when (s.defaultLang) { "ru" -> "Russian"; "cs" -> "Czech"; else -> "English" }
        val history = historyBlock(context, historyKey)
        // Системное предупреждение (№ maxReplies+1): отдельный промпт, но с контекстом и фактами.
        if (warn) {
            val warnPrompt = s.promptWarn.replace("{hours}", s.timeoutHours.toString())
            return """
            $warnPrompt

            Facts about the business (use if relevant):
            ${AboutInfo.text(context, s.businessInfo)}
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
