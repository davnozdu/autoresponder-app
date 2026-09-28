package com.davnozdu.autoresponder.llm

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Локальная дешифровка речи — NVIDIA Parakeet-TDT-0.6B-v3 (nemo_transducer, int8) через
 * sherpa-onnx: офлайн, без интернета, после того как модель один раз скачана. Та же модель,
 * что официальный пример sherpa-onnx (csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) —
 * ровно формат encoder/decoder/joiner/tokens.txt, который ждёт их Kotlin API.
 *
 * Модель НЕ входит в APK (~670МБ) — качается по требованию в files/parakeet/ при первом
 * включении локального режима (Настройки → Дешифровка записей), а не при каждой установке.
 */
object LocalTranscriber {
    private const val MODEL_BASE_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    // Без callTimeout (0 = без ограничения на весь вызов) — файлы под 650МБ, суммарное время
    // зависит от скорости сети, а не от разумного фиксированного потолка. readTimeout всё
    // равно оборвёт зависшее соединение, если поток данных остановится совсем.
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "parakeet").apply { mkdirs() }

    /** Все 4 файла модели на месте и не пустые — готова к использованию. */
    fun isModelReady(ctx: Context): Boolean =
        MODEL_FILES.all { File(modelDir(ctx), it).let { f -> f.exists() && f.length() > 0 } }

    fun modelSizeMb(ctx: Context): Long =
        MODEL_FILES.sumOf { File(modelDir(ctx), it).let { f -> if (f.exists()) f.length() else 0L } } / (1024 * 1024)

    /** Скачивает недостающие файлы модели (уже скачанные — пропускает). Блокирующий вызов —
     *  звать из withContext(Dispatchers.IO), как и [transcribe]. [onProgress] — (имя файла,
     *  скачано МБ, всего МБ по Content-Length; -1, если сервер его не прислал). */
    fun downloadModel(ctx: Context, onProgress: (String, Long, Long) -> Unit) {
        val dir = modelDir(ctx)
        for (name in MODEL_FILES) {
            val dst = File(dir, name)
            if (dst.exists() && dst.length() > 0) continue
            val tmp = File(dir, "$name.part")
            val req = Request.Builder().url("$MODEL_BASE_URL/$name").build()
            downloadClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code} при скачивании $name")
                val body = r.body ?: error("Пустой ответ при скачивании $name")
                val totalMb = body.contentLength().let { if (it > 0) it / (1024 * 1024) else -1L }
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress(name, done / (1024 * 1024), totalMb)
                        }
                    }
                }
            }
            if (!tmp.renameTo(dst)) error("Не удалось завершить запись $name")
        }
    }

    /** Удалить скачанную модель (освободить место), например при смене решения. */
    fun deleteModel(ctx: Context) {
        release()
        modelDir(ctx).deleteRecursively()
    }

    // Загрузка модели в память (десятки-сотни МБ, инициализация ONNX-сессий) стоит недёшево —
    // держим один экземпляр, а не пересоздаём на каждую дешифровку.
    @Volatile private var recognizer: OfflineRecognizer? = null

    private fun getRecognizer(ctx: Context): OfflineRecognizer {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            val dir = modelDir(ctx)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = 2,
                )
            )
            val r = OfflineRecognizer(config = config)
            recognizer = r
            return r
        }
    }

    /** Освободить нативный recognizer (например, после смены/удаления модели). */
    fun release() {
        synchronized(this) {
            recognizer?.release()
            recognizer = null
        }
    }

    /** @return текст расшифровки. Язык не передаётся отдельно — Parakeet сам определяет
     *  его из 25 поддерживаемых европейских (включая ru/cs/en). Блокирующий вызов. */
    fun transcribe(ctx: Context, audioFile: File): String {
        if (!isModelReady(ctx)) error("Локальная модель ещё не скачана (Настройки → Дешифровка записей)")
        if (!audioFile.exists() || audioFile.length() == 0L) error("Файл записи не найден")
        val rec = getRecognizer(ctx)
        val wave = WaveReader.readWave(audioFile.absolutePath)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            if (text.isBlank()) error("Пустой ответ дешифровки")
            return text
        } finally {
            stream.release()
        }
    }
}
