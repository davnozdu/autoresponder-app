package com.davnozdu.autoresponder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-совместимый API: /v1/models, /v1/chat/completions. */
class OpenAiProvider(private val cfg: LlmConfig) : LlmProvider {

    private fun base() = cfg.baseUrl.ifBlank { "https://api.openai.com" }.trimEnd('/')

    override fun listModels(): List<String> {
        val req = Request.Builder().url("${base()}/v1/models")
            .header("Authorization", "Bearer ${cfg.apiKey}").get().build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string()?.take(200)}")
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("data") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.ifBlank { null } }
        }
    }

    override fun generate(prompt: String, maxChars: Int, think: Boolean): String? {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt)
        )
        // think=true — большой бюджет токенов на размышление (ответ обрежется под SMS вызывающим кодом).
        val maxTokens = if (think) 8192 else (maxChars / 2).coerceAtLeast(64)
        val body = JSONObject()
            .put("model", cfg.model.ifBlank { "gpt-4o-mini" })
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .toString().toRequestBody(Http.JSON.toMediaType())
        val req = Request.Builder().url("${base()}/v1/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}").post(body).build()
        Http.client(think).newCall(req).execute().use { r ->
            val raw = r.body?.string()
            // Ошибку API поднимаем наверх: раньше «модели нет у этого провайдера» или
            // «неверный ключ» превращались в молчаливый null и в офлайн-шаблон.
            if (!r.isSuccessful) error("HTTP ${r.code}: ${raw?.take(200)?.replace('\n', ' ')}")
            val o = JSONObject(raw ?: "{}")
            val choices = o.optJSONArray("choices") ?: error("нет choices в ответе")
            if (choices.length() == 0) error("пустой список choices")
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
            val content = msg?.optString("content")?.trim()?.ifBlank { null }
            if (content != null) return content
            // Reasoning-модели кладут размышление в reasoning_content; если сам ответ пуст,
            // значит бюджет токенов ушёл на «мысли» — это стоит увидеть в журнале.
            val reasoned = msg?.optString("reasoning_content")?.isNotBlank() == true
            error(if (reasoned) "пустой ответ: бюджет токенов ушёл в reasoning"
                  else "пустой ответ модели")
        }
    }
}
