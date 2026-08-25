package com.davnozdu.autoresponder.store

import android.content.Context
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.LlmConfig
import com.davnozdu.autoresponder.llm.LlmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Чат-вопросы к истории звонков/SMS через LLM. */
object HistoryQa {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun ask(context: Context, question: String, history: List<Pair<String, String>>): String {
        val s = Settings(context)
        if (!s.llmEnabled || s.llmModel.isBlank()) return "LLM не настроена — чат недоступен."

        val events = HistoryDb.get(context).recentEvents(500)
        val data = buildString {
            for (e in events) {
                val who = e.name ?: e.number
                val dir = if (e.direction == "in") "входящее" else "исходящее"
                append(fmt.format(Date(e.ts))).append(" | ").append(who)
                    .append(" | ").append(e.channel).append(" ").append(dir)
                    .append(" | ").append(e.body.take(80)).append("\n")
                if (length > 9000) break
            }
        }
        val prior = history.takeLast(6).joinToString("\n") { "${it.first}: ${it.second}" }
        val prompt = """
            Ты — помощник по истории звонков и сообщений владельца телефона.
            Отвечай кратко и точно на русском, опираясь ТОЛЬКО на данные ниже. Если данных не хватает — так и скажи.
            Текущее время: ${fmt.format(Date())}.

            ДАННЫЕ (сверху — новее): каждая строка = дата | контакт | канал направление | текст
            $data

            ${if (prior.isNotBlank()) "Предыдущий диалог:\n$prior\n" else ""}
            Вопрос: $question
        """.trimIndent()

        return try {
            val cfg = LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
            LlmFactory.create(cfg).generate(prompt, 2000) ?: "Не удалось получить ответ."
        } catch (e: Exception) { "Ошибка: ${e.message}" }
    }
}
