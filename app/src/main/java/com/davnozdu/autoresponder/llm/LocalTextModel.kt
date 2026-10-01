package com.davnozdu.autoresponder.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Gemma 4 E2B for text generation. No worker, timer or network activity while idle. */
object LocalTextModel {
    private const val NAME = "gemma-4-E2B-it.litertlm"
    private const val SIZE = 2_588_147_712L
    private const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$NAME"
    private const val IDLE_MS = 60_000L
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleUnload = Runnable { release() }
    private val inferenceLock = Any()
    @Volatile private var downloading = false
    private var engine: Engine? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "gemma4").apply { mkdirs() }
    private fun model(ctx: Context) = File(dir(ctx), NAME)
    fun isReady(ctx: Context) = model(ctx).length() == SIZE
    fun sizeMb(ctx: Context) = model(ctx).length() / (1024 * 1024)

    /** Called only by an explicit settings action. Partial download resumes with HTTP Range. */
    fun download(ctx: Context, onProgress: (Long, Long) -> Unit) {
        synchronized(this) {
            if (downloading) error("Модель уже скачивается")
            downloading = true
        }
        try {
            if (isReady(ctx)) return
            val dst = model(ctx)
            val part = File(dir(ctx), "$NAME.part")
            var start = part.length().takeIf { it in 1 until SIZE } ?: 0L
            if (start == 0L && part.exists()) part.delete()
            val request = Request.Builder().url(URL).apply {
                if (start > 0) header("Range", "bytes=$start-")
            }.build()
            client.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206)
                    error("HTTP ${response.code} при скачивании модели")
                if (start > 0 && (response.code != 206 ||
                            !response.header("Content-Range").orEmpty().startsWith("bytes $start-"))) {
                    part.delete()
                    error("Сервер не подтвердил продолжение загрузки; нажмите «Скачать» снова")
                }
                if (response.code == 200) start = 0L
                val body = response.body ?: error("Пустой ответ при скачивании модели")
                java.io.FileOutputStream(part, start > 0).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var done = start
                        var lastReport = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            done += count
                            if (done - lastReport >= 4L * 1024 * 1024) {
                                onProgress(done, SIZE)
                                lastReport = done
                            }
                        }
                    }
                }
            }
            if (part.length() != SIZE) error("Загрузка прервана: ${part.length()} из $SIZE байт. Можно продолжить.")
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != SHA256) {
                part.delete()
                error("SHA-256 модели не совпал. Повреждённый файл удалён")
            }
            if (!part.renameTo(dst)) error("Не удалось сохранить модель")
            onProgress(SIZE, SIZE)
        } finally {
            downloading = false
        }
    }

    fun release(): Unit = synchronized(inferenceLock) {
        idleHandler.removeCallbacks(idleUnload)
        engine?.close()
        engine = null
    }

    fun delete(ctx: Context) {
        release()
        dir(ctx).deleteRecursively()
    }

    /** Serialized: one native engine and one request at a time; each conversation is fresh. */
    fun generate(ctx: Context, prompt: String, system: String): String? = synchronized(inferenceLock) {
        if (!isReady(ctx)) return@synchronized null
        idleHandler.removeCallbacks(idleUnload)
        try {
            val current = engine ?: Engine(EngineConfig(
                modelPath = model(ctx).absolutePath,
                backend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                cacheDir = ctx.cacheDir.absolutePath,
            )).also { it.initialize(); engine = it }
            current.createConversation(ConversationConfig(
                systemInstruction = Contents.of(system),
            )).use { conversation ->
                conversation.sendMessage(prompt).toString().trim().ifBlank { null }
            }
        } finally {
            idleHandler.postDelayed(idleUnload, IDLE_MS)
        }
    }

    /** Gemma audio input is limited to 30 seconds. Split longer recordings into 25s WAVs. */
    fun transcribe(ctx: Context, audioFile: File): String = synchronized(inferenceLock) {
        if (!isReady(ctx)) error("Сначала скачайте Gemma 4 E2B в настройках LLM")
        val seconds = com.davnozdu.autoresponder.call.AudioConvert.probeDurationSec(audioFile.absolutePath)
        if (seconds != null && seconds > 3600) error("Запись длиннее 60 минут")
        val (samples, rate) = com.davnozdu.autoresponder.call.AudioConvert.decodeToMono(audioFile.absolutePath)
            ?: error("Не удалось прочитать аудиофайл")
        if (rate <= 0 || samples.size / rate > 3600) error("Запись длиннее 60 минут")
        val trimmed = com.davnozdu.autoresponder.call.GreetingTrim.trimForTranscription(
            audioFile.absolutePath, samples, rate)
        if (trimmed.size < samples.size &&
            com.davnozdu.autoresponder.call.GreetingTrim.isNegligible(trimmed, rate))
            error("Сообщение не записано после приветствия")
        idleHandler.removeCallbacks(idleUnload)
        try {
            val current = engine ?: Engine(EngineConfig(
                modelPath = model(ctx).absolutePath,
                backend = Backend.CPU(), audioBackend = Backend.CPU(),
                cacheDir = ctx.cacheDir.absolutePath,
            )).also { it.initialize(); engine = it }
            val parts = StringBuilder()
            val chunkSamples = 25 * rate
            var offset = 0
            while (offset < trimmed.size) {
                val length = minOf(chunkSamples, trimmed.size - offset)
                val wav = wav16k(trimmed, rate, offset, length)
                val text = current.createConversation().use { conversation ->
                    conversation.sendMessage(Contents.of(
                        Content.AudioBytes(wav),
                        Content.Text("Transcribe the speech exactly. Output only the transcript in the original language."),
                    )).toString().trim()
                }
                if (text.isNotBlank()) {
                    if (parts.isNotEmpty()) parts.append(' ')
                    parts.append(text)
                }
                offset += length
            }
            parts.toString().ifBlank { error("Gemma не распознала речь") }
        } finally {
            idleHandler.postDelayed(idleUnload, IDLE_MS)
        }
    }

    private fun wav16k(src: ShortArray, rate: Int, offset: Int, count: Int): ByteArray {
        val frames = ((count.toLong() * 16000) / rate).toInt().coerceAtLeast(1)
        val dataSize = frames * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(16000).putInt(32000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        for (i in 0 until frames) {
            val index = offset + ((i.toLong() * rate) / 16000).toInt().coerceAtMost(count - 1)
            buffer.putShort(src[index])
        }
        return buffer.array()
    }
}
