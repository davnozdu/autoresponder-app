package com.davnozdu.autoresponder.respond

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.ReplyStore
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.LlmConfig
import com.davnozdu.autoresponder.llm.LlmFactory
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.LangDetect
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.rules.SkipPolicy
import com.davnozdu.autoresponder.rules.SimUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Kind { CALL, SMS }

/** Центральная логика автоответа. */
object Responder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(context: Context, number: String?, incomingText: String?, kind: Kind) {
        val app = context.applicationContext
        scope.launch { process(app, number, incomingText, kind) }
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

    private suspend fun process(context: Context, number: String?, incomingText: String?, kind: Kind) {
        val s = Settings(context)
        val log = EventLog(context)
        val tag = if (kind == Kind.CALL) "CALL" else "SMS"
        val from = number ?: "?"

        if (!s.enabled) return
        if (kind == Kind.CALL && !s.respondCalls) return
        if (kind == Kind.SMS && !s.respondSms) return

        val closedReason = ClosedState.reason(context, s)
        if (closedReason == null) {
            log.add("$tag $from — открыто, пропуск"); return
        }
        if (PhoneMask.isAlphanumericSender(number)) {
            log.add("$tag $from — буквенный отправитель, пропуск"); return
        }
        if (!PhoneMask.matches(number, s.allowedPrefixes)) {
            log.add("$tag $from — не подходит под маску стран, пропуск"); return
        }
        SkipPolicy.reason(context, number, s, kind == Kind.CALL)?.let { r ->
            log.add("$tag $from — $r, пропуск"); return
        }
        val norm = PhoneMask.normalize(number)!!

        val store = ReplyStore(context)
        if (!store.canReply(norm, s.maxReplies, s.timeoutHours)) {
            log.add("$tag $norm — лимит ${s.maxReplies}, таймаут ${s.timeoutHours}ч, пропуск"); return
        }

        if (kind == Kind.SMS && !Dedup.claim("sms:$norm:$incomingText")) {
            log.add("$tag $norm — дубль (уже обработано уведомлением), пропуск"); return
        }

        // Пауза перед отправкой — стабильность: даём телефонии/радиомодулю
        // устояться после сброса звонка или приёма SMS.
        if (s.replyDelayMs > 0) delay(s.replyDelayMs)

        val returning = store.count(norm) > 0
        val reply = buildReply(context, s, incomingText, kind, returning)
        val prefixed = applyPrefix(s.aiPrefix, reply)
        val clamped = SegmentBudget.clampToBudget(prefixed, s.maxSegments)

        val subId = SimUtil.resolveSubId(context, s.smsSlot)
        val segs = SmsSender.send(context, norm, clamped, subId)
        if (segs >= 0) {
            store.markReplied(norm, s.timeoutHours)
            log.add("$tag $norm — ответ (закрыто:$closedReason, $segs сег, #${store.count(norm)}/${s.maxReplies}): $clamped")
        } else {
            log.add("$tag $norm — ОШИБКА отправки SMS")
        }
    }

    /** Публичная сборка финального ответа (LLM/шаблон + префикс + обрезка сегментов). */
    fun composeReply(context: Context, s: Settings, incomingText: String?, kind: Kind, returning: Boolean): String {
        val reply = buildReply(context, s, incomingText, kind, returning)
        return SegmentBudget.clampToBudget(applyPrefix(s.aiPrefix, reply), s.maxSegments)
    }

    private fun buildReply(
        context: Context, s: Settings, incomingText: String?, kind: Kind, returning: Boolean
    ): String {
        if (s.llmEnabled && NetworkUtil.isOnline(context) && s.llmModel.isNotBlank()) {
            try {
                // Бюджет с учётом префикса «Ответ от AI:» (консервативно как UCS-2).
                val prefixLen = if (s.aiPrefix.isBlank()) 0 else s.aiPrefix.length + 1
                val budget = (SegmentBudget.charBudget("ru", s.maxSegments) - prefixLen).coerceAtLeast(40)
                val prompt = buildPrompt(s, incomingText, kind, budget, returning)
                val cfg = LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                val out = LlmFactory.create(cfg).generate(prompt, budget)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM пустой ответ, шаблон")
            } catch (e: Exception) {
                EventLog(context).add("LLM ошибка: ${e.message}, шаблон")
            }
        }
        // Офлайн: язык по эвристике, затем шаблон.
        val lang = if (kind == Kind.SMS && !incomingText.isNullOrBlank())
            LangDetect.detect(incomingText, s.defaultLang) else s.defaultLang
        return s.template(lang)
    }

    /** Добавляет пометку ИИ в начало, без двойных пробелов. */
    private fun applyPrefix(prefix: String, text: String): String {
        val p = prefix.trim()
        return if (p.isEmpty()) text else "$p $text"
    }

    private fun buildPrompt(
        s: Settings, incomingText: String?, kind: Kind, budget: Int, returning: Boolean
    ): String {
        val defName = when (s.defaultLang) { "ru" -> "Russian"; "cs" -> "Czech"; else -> "English" }
        return if (kind == Kind.SMS && !incomingText.isNullOrBlank()) {
            val ret = if (returning) "This number has contacted us before (returning contact)."
                      else "This is a new contact (first message)."
            """
            ${s.promptSms}

            Facts about the business (use them to answer):
            ${s.businessInfo}

            Customer's SMS: "$incomingText"
            $ret
            Detect the language of the customer's message (Russian, Ukrainian, Czech, English, or any other) and reply in THAT SAME language. If you cannot determine it, reply in $defName.
            Use the content of the SMS as context. Keep it brief and polite.
            Hard limit: at most $budget characters. One short message, no signature, no emojis, plain text only. Do NOT add any prefix yourself.
            """.trimIndent()
        } else {
            """
            ${s.promptCall}

            Reply in $defName.
            Hard limit: at most $budget characters. No signature, no emojis, plain text only. Do NOT add any prefix yourself.
            """.trimIndent()
        }
    }
}
