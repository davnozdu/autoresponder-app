package com.davnozdu.autoresponder.llm

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings

/**
 * Генерация ответа с фолбэком: основной канал → резервный → null (вызывающий код отдаёт
 * стандартную заглушку из настроек). Каждый вызов ограничен [Http] callTimeout (~13с), поэтому
 * «тупящий» провайдер не держит очередь: молчит основной ~13с → пробуем резервный ~13с →
 * оба молчат → заглушка. Проверка сети делается выше (buildReply), офлайн → сразу заглушка.
 */
object Llm {
    private val quotaLock = Any()

    /** Атомарно проверяет и расходует дневной лимит. true — можно обращаться к LLM. */
    private fun consumeQuota(s: Settings): Boolean {
        if (s.llmDailyCap <= 0) return true  // 0 — без лимита и без счётчика
        synchronized(quotaLock) {
            val today = (System.currentTimeMillis() / 86_400_000L).toInt()
            if (s.llmDay != today) { s.llmDay = today; s.llmCount = 0 }
            if (s.llmCount >= s.llmDailyCap) return false
            s.llmCount = s.llmCount + 1
            return true
        }
    }

    fun generate(context: Context, prompt: String, maxChars: Int): String? {
        val s = Settings(context)
        if (!consumeQuota(s)) return null

        // Основной канал.
        if (s.llmModel.isNotBlank()) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                ).generate(prompt, maxChars)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM основной пуст/таймаут → резервный")
            } catch (e: Exception) {
                EventLog(context).add("LLM основной ошибка (${e.javaClass.simpleName}) → резервный")
            }
        }

        // Резервный канал.
        if (s.llm2Enabled && s.llm2Model.isNotBlank()) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llm2Provider, s.llm2BaseUrl, s.llm2ApiKey, s.llm2Model)
                ).generate(prompt, maxChars)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM резервный пуст/таймаут → заглушка")
            } catch (e: Exception) {
                EventLog(context).add("LLM резервный ошибка (${e.javaClass.simpleName}) → заглушка")
            }
        }
        return null  // оба канала молчат → вызывающий код отдаёт шаблон-заглушку
    }
}
