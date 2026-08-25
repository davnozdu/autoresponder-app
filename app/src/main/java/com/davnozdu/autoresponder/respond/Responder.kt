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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class Kind { CALL, SMS }

/** Центральная логика автоответа. */
object Responder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(context: Context, number: String?, incomingText: String?, kind: Kind) {
        val app = context.applicationContext
        scope.launch { process(app, number, incomingText, kind) }
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
            val reply = buildReply(app, s, incomingText, kind)
            val clamped = SegmentBudget.clampToBudget(reply, s.maxSegments)
            val segs = if (send) SmsSender.send(app, norm, clamped) else SmsSender.segmentCount(app, clamped)
            store.markReplied(norm, s.timeoutHours)
            val mode = if (send) "SENT" else "dry"
            log.add("TEST[$kind] $norm $mode #${store.count(norm)}/${s.maxReplies} seg=$segs len=${clamped.length}")
        }
    }

    private fun process(context: Context, number: String?, incomingText: String?, kind: Kind) {
        val s = Settings(context)
        val log = EventLog(context)
        val tag = if (kind == Kind.CALL) "CALL" else "SMS"
        val from = number ?: "?"

        if (!s.enabled) return
        if (kind == Kind.CALL && !s.respondCalls) return
        if (kind == Kind.SMS && !s.respondSms) return

        if (!ClosedState.isClosed(context, s)) {
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

        val reply = buildReply(context, s, incomingText, kind)
        val clamped = SegmentBudget.clampToBudget(reply, s.maxSegments)

        val segs = SmsSender.send(context, norm, clamped, subId = -1)
        if (segs >= 0) {
            store.markReplied(norm, s.timeoutHours)
            log.add("$tag $norm — отправлено [$segs сег., #${store.count(norm)}/${s.maxReplies}]: ${clamped.take(40)}…")
        } else {
            log.add("$tag $norm — ОШИБКА отправки SMS")
        }
    }

    private fun buildReply(
        context: Context, s: Settings, incomingText: String?, kind: Kind
    ): String {
        if (s.llmEnabled && NetworkUtil.isOnline(context) && s.llmModel.isNotBlank()) {
            try {
                // Консервативный бюджет (UCS-2), т.к. язык ответа заранее неизвестен.
                val budget = SegmentBudget.charBudget("ru", s.maxSegments)
                val prompt = buildPrompt(incomingText, kind, budget, s.defaultLang)
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

    private fun buildPrompt(incomingText: String?, kind: Kind, budget: Int, defaultLang: String): String {
        val defName = when (defaultLang) { "ru" -> "Russian"; "cs" -> "Czech"; else -> "English" }
        return if (kind == Kind.SMS && !incomingText.isNullOrBlank()) {
            """
            You are an automatic SMS reply system for a business that is currently CLOSED.
            A customer sent this message:
            "$incomingText"
            Detect the language of the customer's message (it may be Russian, Ukrainian, Czech, English or any other) and write your reply in THAT SAME language.
            If you cannot confidently determine the language, reply in $defName.
            Say we are closed now and ask them to contact us again tomorrow, or briefly answer their question.
            Hard limit: at most $budget characters. One short message, no signature, no emojis, plain text only.
            """.trimIndent()
        } else {
            """
            You are an automatic SMS reply system for a business that is currently CLOSED.
            A customer tried to call but we could not answer.
            Write ONE short, polite SMS reply in $defName.
            Say we are closed now and ask them to call back tomorrow.
            Hard limit: at most $budget characters. No signature, no emojis, plain text only.
            """.trimIndent()
        }
    }
}
