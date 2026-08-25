package com.davnozdu.autoresponder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/** Локальный Ollama: /api/tags, /api/generate. Ключ не нужен. */
class OllamaProvider(private val cfg: LlmConfig) : LlmProvider {

    private fun base() = cfg.baseUrl.trimEnd('/')

    override fun listModels(): List<String> {
        val req = Request.Builder().url("${base()}/api/tags").get().build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("models") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name") }
        }
    }

    override fun generate(prompt: String, maxChars: Int): String? {
        val body = JSONObject()
            .put("model", cfg.model.ifBlank { "llama3" })
            .put("prompt", prompt)
            .put("stream", false)
            .put("options", JSONObject().put("num_predict", (maxChars / 2).coerceAtLeast(64)))
            .toString().toRequestBody(Http.JSON.toMediaType())
        val req = Request.Builder().url("${base()}/api/generate").post(body).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return JSONObject(r.body?.string() ?: "{}").optString("response").trim().ifBlank { null }
        }
    }
}
