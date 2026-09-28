package com.davnozdu.autoresponder.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Дешифровка записи автоответчика в текст — Groq `/openai/v1/audio/transcriptions`
 * (Whisper, OpenAI-совместимый multipart-эндпоинт).
 *
 * Язык НЕ передаётся параметром намеренно: приветствие может звучать на одном языке
 * (например, чешском), а сам клиент — оставить сообщение на другом (английском/русском).
 * Без явного `language` Whisper определяет его сам по содержимому, что и нужно здесь.
 */
object Transcriber {
    // Таймауты шире, чем у Http.client в этом же пакете (тот рассчитан на короткий чат-запрос
    // без вложений) — тут заливается сам аудиофайл (до нескольких МБ) и ждётся распознавание.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(provider: String): String = when (provider) {
        "groq" -> "https://api.groq.com/openai/v1"
        else -> error("Неизвестный провайдер дешифровки: $provider")
    }

    /** @return текст расшифровки. Бросает исключение с человекочитаемой причиной при ошибке —
     *  вызывающий код (UI) сам решает, что показать (сообщение поверх кнопки). */
    fun transcribe(provider: String, apiKey: String, model: String, audioFile: File): String {
        if (apiKey.isBlank()) error("Не задан API-ключ дешифровки (Настройки → Дешифровка записей)")
        if (!audioFile.exists() || audioFile.length() == 0L) error("Файл записи не найден")
        // Обрезка приветствия ПЕРЕД загрузкой — сам файл записи не меняется (см. GreetingTrim).
        // trimmedCopyForUpload сама бросает понятную ошибку, если после обрезки реального
        // сообщения не осталось (клиент положил трубку во время приветствия) — короткое
        // замыкание до платного запроса в облако. null — обрезать нечего/сайдкара нет, грузим
        // audioFile как раньше.
        val trimmed = com.davnozdu.autoresponder.call.GreetingTrim.trimmedCopyForUpload(audioFile)
        val uploadFile = trimmed ?: audioFile
        try {
            // Записи бывают и .wav (свой pal_record fallback), и .mp3 (штатный рекордер
            // OxygenOS) — раньше здесь всегда стоял audio/wav независимо от реального формата
            // файла. Обрезанная копия всегда WAV (см. GreetingTrim.trimmedCopyForUpload).
            val mediaType = if (trimmed != null) "audio/wav" else when (audioFile.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a", "aac" -> "audio/aac"
                else -> "audio/wav"
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", uploadFile.name, uploadFile.asRequestBody(mediaType.toMediaType()))
                .addFormDataPart("model", model.ifBlank { "whisper-large-v3-turbo" })
                .addFormDataPart("response_format", "json")
                .build()
            val req = Request.Builder().url("${baseUrl(provider)}/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body).build()
            client.newCall(req).execute().use { r ->
                val raw = r.body?.string()
                if (!r.isSuccessful) error("HTTP ${r.code}: ${raw?.take(300)?.replace('\n', ' ')}")
                val text = JSONObject(raw ?: "{}").optString("text").trim()
                if (text.isBlank()) error("Пустой ответ дешифровки")
                return text
            }
        } finally {
            if (trimmed != null) trimmed.delete()
        }
    }
}
