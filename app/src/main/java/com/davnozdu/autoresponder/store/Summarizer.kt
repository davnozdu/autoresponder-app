package com.davnozdu.autoresponder.store

import android.content.Context
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.LlmConfig
import com.davnozdu.autoresponder.llm.LlmFactory

/** Краткий пересказ ветки переписки через настроенную LLM. */
object Summarizer {

    fun summarize(context: Context, items: List<HistItem>): String {
        if (items.isEmpty()) return "Нет сообщений за выбранный период."
        val s = Settings(context)
        if (!s.llmEnabled || s.llmModel.isBlank())
            return "LLM не настроена — пересказ недоступен."

        val who = items.firstOrNull { !it.name.isNullOrBlank() }?.name ?: items.first().number
        val thread = items.joinToString("\n") { it ->
            val dir = if (it.direction == "in") "Клиент" else "Мы"
            "[$dir/${it.channel}] ${it.body}"
        }.take(6000)

        val prompt = """
            Кратко и по делу перескажи переписку с "$who" на русском.
            Укажи: кто это, о чём общались, что человек хотел/спрашивал, есть ли открытые вопросы или что нужно сделать.
            Только суть, без воды, до 8 предложений.

            Переписка (снизу вверх по времени):
            $thread
        """.trimIndent()

        return try {
            val cfg = LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
            LlmFactory.create(cfg).generate(prompt, 1500) ?: "Не удалось получить пересказ (LLM недоступна)."
        } catch (e: Exception) {
            "Ошибка пересказа: ${e.message}"
        }
    }
}
