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
        val norm = PhoneMask.normalize(number)!!

        val store = ReplyStore(context)
        if (store.isOnCooldown(norm, s.cooldownHours)) {
            log.add("$tag $norm — cooldown, пропуск"); return
        }

        val lang = if (kind == Kind.SMS && !incomingText.isNullOrBlank())
            LangDetect.detect(incomingText, s.defaultLang) else s.defaultLang

        val reply = buildReply(context, s, lang, incomingText, kind)
        val clamped = SegmentBudget.clampToBudget(reply, lang, s.maxSegments)

        val segs = SmsSender.send(context, norm, clamped, subId = -1)
        if (segs >= 0) {
            store.markReplied(norm)
            log.add("$tag $norm — отправлено [$lang, $segs сег.]: ${clamped.take(40)}…")
        } else {
            log.add("$tag $norm — ОШИБКА отправки SMS")
        }
    }

    private fun buildReply(
        context: Context, s: Settings, lang: String, incomingText: String?, kind: Kind
    ): String {
        if (s.llmEnabled && NetworkUtil.isOnline(context) && s.llmModel.isNotBlank()) {
            try {
                val budget = SegmentBudget.charBudget(lang, s.maxSegments)
                val prompt = buildPrompt(lang, incomingText, kind, budget)
                val cfg = LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                val out = LlmFactory.create(cfg).generate(prompt, budget)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM пустой ответ, шаблон")
            } catch (e: Exception) {
                EventLog(context).add("LLM ошибка: ${e.message}, шаблон")
            }
        }
        return s.template(lang)
    }

    private fun buildPrompt(lang: String, incomingText: String?, kind: Kind, budget: Int): String {
        val langName = when (lang) { "ru" -> "Russian"; "cs" -> "Czech"; else -> "English" }
        val ctx = if (kind == Kind.SMS && !incomingText.isNullOrBlank())
            "The customer sent this SMS: \"$incomingText\"."
        else
            "The customer tried to call but we could not answer."
        return """
            You are an automatic reply system for a business that is currently CLOSED.
            $ctx
            Write ONE short, polite SMS reply in $langName.
            Say we are closed now and ask them to contact us again tomorrow (or answer their question briefly if possible).
            Hard limit: at most $budget characters. No greetings signature, no emojis, plain text only.
        """.trimIndent()
    }
}
