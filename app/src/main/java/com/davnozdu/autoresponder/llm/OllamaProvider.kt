package com.davnozdu.autoresponder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ollama: локальный (без ключа) и Ollama Cloud (https://ollama.com + Bearer-ключ).
 * listModels -> /api/tags ; generate -> /api/chat. Ключ добавляется, если задан.
 */
class OllamaProvider(private val cfg: LlmConfig) : LlmProvider {

    private fun base() = cfg.baseUrl.trimEnd('/')

    private fun Request.Builder.auth(): Request.Builder {
        if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}")
        return this
    }

    override fun listModels(): List<String> {
        val req = Request.Builder().url("${base()}/api/tags").auth().get().build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("models") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name") }
        }
    }

    override fun generate(prompt: String, maxChars: Int, think: Boolean): String? {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt)
        )
        // think=true: даём большой бюджет токенов (пусть reasoning-модель думает); ответ обрежется под SMS.
        // think=false: явно отключаем размышление и держим короткий бюджет.
        val numPredict = if (think) 8192 else (maxChars / 2).coerceAtLeast(64)
        val body = JSONObject()
            .put("model", cfg.model.ifBlank { "gemma3" })
            .put("messages", messages)
            .put("stream", false)
            .put("think", think)
            .put("options", JSONObject().put("num_predict", numPredict))
            .toString().toRequestBody(Http.JSON.toMediaType())
        val req = Request.Builder().url("${base()}/api/chat").auth().post(body).build()
        Http.client(think).newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return JSONObject(r.body?.string() ?: "{}")
                .optJSONObject("message")?.optString("content")?.trim()?.ifBlank { null }
        }
    }
}
