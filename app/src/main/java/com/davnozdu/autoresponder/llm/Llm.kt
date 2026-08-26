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

    /** Номер локальных суток (не UTC): иначе счётчик сбрасывался ночью по UTC, а не в полночь. */
    private fun localDay(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Атомарно проверяет и расходует дневной лимит. true — можно обращаться к LLM. */
    private fun consumeQuota(s: Settings): Boolean {
        if (s.llmDailyCap <= 0) return true  // 0 — без лимита и без счётчика
        synchronized(quotaLock) {
            val today = localDay()
            if (s.llmDay != today) { s.llmDay = today; s.llmCount = 0 }
            if (s.llmCount >= s.llmDailyCap) return false
            s.llmCount = s.llmCount + 1
            return true
        }
    }

    /** Настроен ли хоть один канал (основной или резервный) — для экранов пересказа/чата. */
    fun isConfigured(context: Context): Boolean {
        val s = Settings(context)
        return s.llmEnabled && (s.llmModel.isNotBlank() || (s.llm2Enabled && s.llm2Model.isNotBlank()))
    }

    fun generate(context: Context, prompt: String, maxChars: Int): String? {
        val s = Settings(context)
        val primary = s.llmModel.isNotBlank()
        val backup = s.llm2Enabled && s.llm2Model.isNotBlank()
        // Нечего спрашивать — не трогаем счётчик вообще.
        if (!primary && !backup) return null
        if (!consumeQuota(s)) return null

        // Основной канал.
        if (primary) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                ).generate(prompt, maxChars, s.llmThink)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM основной [${s.llmProvider}/${s.llmModel}] пуст/таймаут → резервный")
            } catch (e: Exception) {
                EventLog(context).add("LLM основной [${s.llmProvider}/${s.llmModel}] ошибка: ${e.message} → резервный")
            }
        }

        // Резервный канал.
        if (backup) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llm2Provider, s.llm2BaseUrl, s.llm2ApiKey, s.llm2Model)
                ).generate(prompt, maxChars, s.llmThink)
                if (!out.isNullOrBlank()) return out
                EventLog(context).add("LLM резервный [${s.llm2Provider}/${s.llm2Model}] пуст/таймаут → заглушка")
            } catch (e: Exception) {
                EventLog(context).add("LLM резервный [${s.llm2Provider}/${s.llm2Model}] ошибка: ${e.message} → заглушка")
            }
        }
        return null  // оба канала молчат → вызывающий код отдаёт шаблон-заглушку
    }
}
