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
            if (!r.isSuccessful) return emptyList()
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("data") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("id") }
        }
    }

    override fun generate(prompt: String, maxChars: Int): String? {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt)
        )
        val body = JSONObject()
            .put("model", cfg.model.ifBlank { "claude-3-5-haiku-latest" })
            .put("max_tokens", (maxChars / 2).coerceAtLeast(64))
            .put("messages", messages)
            .toString().toRequestBody(Http.JSON.toMediaType())
        val req = Request.Builder().url("${base()}/v1/messages")
            .header("x-api-key", cfg.apiKey)
            .header("anthropic-version", version).post(body).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val content = JSONObject(r.body?.string() ?: "{}").optJSONArray("content") ?: return null
            if (content.length() == 0) return null
            return content.getJSONObject(0).optString("text").trim().ifBlank { null }
        }
    }
}
