package com.davnozdu.autoresponder.llm

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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

    // Если свернуть секцию настроек или переключить провайдер туда-обратно во время скачивания,
    // UI-состояние "downloading" в Compose теряется, а сама загрузка в фоновой корутине
    // продолжается — повторное нажатие "Скачать модель" запускало бы ВТОРУЮ параллельную
    // запись в те же .part-файлы (порченый ONNX, sherpa-onnx на загрузке битой модели скорее
    // всего аварийно завершит процесс). Гвард на уровне самого downloadModel — надёжнее, чем
    // полагаться на то, что UI всегда правильно заблокирует повторный тап. Найдено аудитом.
    @Volatile private var downloadInProgress = false

    /** Скачивает недостающие файлы модели (уже скачанные — пропускает). Блокирующий вызов —
     *  звать из withContext(Dispatchers.IO), как и [transcribe]. [onProgress] — (имя файла,
     *  скачано МБ, всего МБ по Content-Length; -1, если сервер его не прислал). */
    fun downloadModel(ctx: Context, onProgress: (String, Long, Long) -> Unit) {
        synchronized(this) {
            if (downloadInProgress) error("Скачивание модели уже идёт")
            downloadInProgress = true
        }
        try {
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
        } finally {
            downloadInProgress = false
        }
    }

    /** Удалить скачанную модель (освободить место), например при смене решения. */
    fun deleteModel(ctx: Context) {
        release()
        modelDir(ctx).deleteRecursively()
    }

    // Загрузка модели в память (десятки-сотни МБ, инициализация ONNX-сессий) стоит недёшево —
    // держим один экземпляр, а не пересоздаём на каждую дешифровку. Но и не держим его вечно:
    // "функция не должна грузить ресурсы телефона" — если дешифровкой не пользовались
    // IDLE_UNLOAD_MS, recognizer сам освобождается фоновым таймером ниже, а не висит в памяти
    // до самой смерти процесса приложения.
    //
    // activeDecodes считает, сколько сейчас идёт transcribe(). Без этого release() (по таймеру
    // ИЛИ вручную — переключение провайдера, удаление модели) мог освободить нативный recognizer
    // ПОКА другой поток ещё внутри rec.decode() на ТОМ ЖЕ объекте — use-after-free в JNI, крах
    // всего процесса (заодно с NotificationListenerService и обработкой звонков). Найдено
    // аудитом. Пока activeDecodes>0, release() не трогает нативный объект — только снимает
    // ссылку в recognizer (новый transcribe() создаст свежий) и откладывает реальный .release()
    // в pendingRelease до того момента, когда последний decode отпустит счётчик.
    @Volatile private var recognizer: OfflineRecognizer? = null
    private var pendingRelease: OfflineRecognizer? = null
    private var activeDecodes = 0
    private const val IDLE_UNLOAD_MS = 60_000L
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleUnload = Runnable { release() }

    private fun buildRecognizer(ctx: Context): OfflineRecognizer {
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
        return OfflineRecognizer(config = config)
    }

    /** Занять recognizer на время одной расшифровки — увеличивает activeDecodes, гарантируя,
     *  что release() (по таймеру или вручную) не освободит его, пока мы им пользуемся. */
    private fun acquireRecognizer(ctx: Context): OfflineRecognizer {
        synchronized(this) {
            idleHandler.removeCallbacks(idleUnload)
            val r = recognizer ?: buildRecognizer(ctx).also { recognizer = it }
            activeDecodes++
            return r
        }
    }

    /** Освободить "занятость" recognizer'а после расшифровки — досрочно завершает отложенный
     *  release() (см. [release]), если он ждал именно этого, и заново ставит таймер простоя. */
    private fun finishDecode() {
        synchronized(this) {
            if (activeDecodes > 0) activeDecodes--
            if (activeDecodes == 0) {
                pendingRelease?.release()
                pendingRelease = null
                idleHandler.postDelayed(idleUnload, IDLE_UNLOAD_MS)
            }
        }
    }

    /** Освободить нативный recognizer — вызывается автоматически по простою, а также вручную
     *  (смена/удаление модели, переключение провайдера обратно на облако). Если прямо сейчас
     *  идёт расшифровка (activeDecodes>0) — не рвём её: снимаем ссылку (следующий transcribe()
     *  создаст новый экземпляр), а сам нативный .release() откладываем до finishDecode(). */
    fun release() {
        synchronized(this) {
            idleHandler.removeCallbacks(idleUnload)
            val r = recognizer ?: return
            recognizer = null
            if (activeDecodes > 0) pendingRelease = r else r.release()
        }
    }

    /** @return текст расшифровки. Язык не передаётся отдельно — Parakeet сам определяет
     *  его из 25 поддерживаемых европейских (включая ru/cs/en). Блокирующий вызов. */
    fun transcribe(ctx: Context, audioFile: File): String {
        if (!isModelReady(ctx)) error("Локальная модель ещё не скачана (Настройки → Дешифровка записей)")
        if (!audioFile.exists() || audioFile.length() == 0L) error("Файл записи не найден")
        val rec = acquireRecognizer(ctx)
        try {
            // Записи автоответчика бывают и .wav (свой pal_record fallback), и .mp3 (штатный
            // рекордер OxygenOS через RecordingLinker) — sherpa-onnx понимает только сырые
            // сэмплы, не контейнеры. decodeToMono уже умеет оба формата (тот же декодер, что
            // готовит приветствия) — раньше здесь был свой WAV-only парсер, падавший на .mp3
            // тем же образом, что и штатный WaveReader на стерео. Найдено живым тестом.
            val (shorts, sampleRate) = com.davnozdu.autoresponder.call.AudioConvert.decodeToMono(audioFile.absolutePath)
                ?: error("Не удалось разобрать аудиофайл")
            val samples = FloatArray(shorts.size) { shorts[it] / 32768f }
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                if (text.isBlank()) error("Пустой ответ дешифровки")
                return text
            } finally {
                stream.release()
            }
        } finally {
            finishDecode()
        }
    }
}
