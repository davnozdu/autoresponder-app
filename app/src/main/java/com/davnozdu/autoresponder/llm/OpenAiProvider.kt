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
            if (!r.isSuccessful) return emptyList()
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
            if (!r.isSuccessful) return null
            val choices = JSONObject(r.body?.string() ?: "{}").optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            return choices.getJSONObject(0).optJSONObject("message")
                ?.optString("content")?.trim()?.ifBlank { null }
        }
    }
}
