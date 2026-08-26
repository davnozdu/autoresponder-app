package com.davnozdu.autoresponder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Claude API: /v1/models, /v1/messages. */
class ClaudeProvider(private val cfg: LlmConfig) : LlmProvider {

    private fun base() = cfg.baseUrl.ifBlank { "https://api.anthropic.com" }.trimEnd('/')
    private val version = "2023-06-01"

    override fun listModels(): List<String> {
        val req = Request.Builder().url("${base()}/v1/models")
            .header("x-api-key", cfg.apiKey)
            .header("anthropic-version", version).get().build()
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
        val maxTokens = if (think) 8192 else (maxChars / 2).coerceAtLeast(64)
        val body = JSONObject()
            .put("model", cfg.model.ifBlank { "claude-3-5-haiku-latest" })
            .put("max_tokens", maxTokens)
            .put("messages", messages)
            .toString().toRequestBody(Http.JSON.toMediaType())
        val req = Request.Builder().url("${base()}/v1/messages")
            .header("x-api-key", cfg.apiKey)
            .header("anthropic-version", version).post(body).build()
        Http.client(think).newCall(req).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${raw?.take(200)?.replace('\n', ' ')}")
            val content = JSONObject(raw ?: "{}").optJSONArray("content") ?: error("нет content в ответе")
            // При reasoning первым блоком идёт "thinking" — берём первый блок с type="text",
            // иначе ответ выглядел пустым и срабатывал фолбэк на шаблон.
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") {
                    b.optString("text").trim().ifBlank { null }?.let { return it }
                }
            }
            error("в ответе нет блока text")
        }
    }
}
