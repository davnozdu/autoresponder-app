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

    /** Размер (байт) и SHA-256 каждого файла модели — зафиксировано вручную (shasum -a 256)
     *  с фактически скачанных файлов текущей ревизии репозитория на HuggingFace. Найдено
     *  повторным аудитом: раньше downloadModel() считал файл валидным по одному факту
     *  «существует и не пуст» — обрезанный/подменённый ONNX принимался бы молча, ошибка
     *  проявилась бы поздно, при создании native recognizer, а не на этапе скачивания. */
    private data class ModelFileInfo(val sizeBytes: Long, val sha256: String)
    private val MODEL_FILE_INFO = mapOf(
        "encoder.int8.onnx" to ModelFileInfo(652184281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        "decoder.int8.onnx" to ModelFileInfo(11845275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        "joiner.int8.onnx" to ModelFileInfo(6355277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        "tokens.txt" to ModelFileInfo(93939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )

    // Без callTimeout (0 = без ограничения на весь вызов) — файлы под 650МБ, суммарное время
    // зависит от скорости сети, а не от разумного фиксированного потолка. readTimeout всё
    // равно оборвёт зависшее соединение, если поток данных остановится совсем.
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "parakeet").apply { mkdirs() }

    /** Все 4 файла модели на месте и нужного размера — готова к использованию. Точный размер,
     *  а не просто «не пустой»: дёшево (одно stat на файл, без чтения содержимого) и ловит
     *  обрезанный/повреждённый файл на каждой проверке, а не только в момент скачивания.
     *  Полный SHA-256 здесь намеренно не считаем — это секунды на файле 650МБ на каждый вызов
     *  (в т.ч. из Compose remember{} на главном потоке), а хэш уже проверен один раз при
     *  скачивании в [downloadModel]; порча уже сохранённого файла на диске (не при скачивании)
     *  — на порядок менее вероятный сценарий на личном устройстве. */
    fun isModelReady(ctx: Context): Boolean =
        MODEL_FILES.all { name ->
            val f = File(modelDir(ctx), name)
            val expected = MODEL_FILE_INFO[name]?.sizeBytes
            f.exists() && (if (expected != null) f.length() == expected else f.length() > 0)
        }

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
                val info = MODEL_FILE_INFO[name]
                // Точный размер, не просто "не пустой" — ранее корректно ДОскачанный, но
                // повреждённый на диске файл молча считался бы готовым навсегда.
                if (dst.exists() && (if (info != null) dst.length() == info.sizeBytes else dst.length() > 0)) continue
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
                if (info != null) {
                    if (tmp.length() != info.sizeBytes) {
                        tmp.delete()
                        error("$name: размер не совпал (ждали ${info.sizeBytes}, получили ${tmp.length()}) — файл повреждён или обрезан")
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    tmp.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != info.sha256) {
                        tmp.delete()
                        error("$name: SHA-256 не совпал (ждали ${info.sha256}, получили $actual) — файл повреждён или подменён")
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
    // activeDecodes защищает только от release() во время decode, но НЕ сериализует сам
    // rec.createStream()/decode() — при двух одновременных вызовах transcribe() оба потока
    // дёргали бы один и тот же нативный OfflineRecognizer параллельно (не гарантированно
    // потокобезопасно в JNI-обвязке sherpa-onnx). Найдено внешним аудитом. Отдельный лок
    // именно на сам декод, не на acquireRecognizer/finishDecode — та синхронизация короткая
    // и не должна блокироваться на время самой расшифровки (секунды).
    private val decodeLock = Any()
    // Настройки уже ограничивают длину исходной записи (voicemailMaxSec/amMaxMessageSec ≤ 300с
    // — см. Settings), так что это скорее защита в глубину на случай будущего пути, который
    // почему-то обойдёт эти лимиты, чем реальный сценарий на сегодня.
    private const val MAX_DECODE_SEC = 600
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
        // Проверка ДО decodeToMono(), а не после: decodeWav16 читает файл целиком
        // (File.readBytes()), decodeCompressed копит весь поток в памяти — прежняя проверка
        // ПОСЛЕ декодирования уже не защищала от OOM на самом декодировании битого/очень
        // длинного файла, полная аллокация происходила раньше, чем срабатывал лимит.
        // probeDurationSec читает только заголовок/метаданные контейнера. null (не удалось
        // оценить) — пропускаем дальше, а не отклоняем: например декодер лучше самой оценки
        // разберётся с нестандартным WAV; экономия памяти тут не гарантирована, но и ложных
        // отказов на вменяемых файлах не будет. Найдено повторным аудитом.
        val probedSec = com.davnozdu.autoresponder.call.AudioConvert.probeDurationSec(audioFile.absolutePath)
        if (probedSec != null && probedSec > MAX_DECODE_SEC) {
            error("Запись слишком длинная для локальной дешифровки (> ${MAX_DECODE_SEC / 60} мин)")
        }
        // Записи автоответчика бывают и .wav (свой pal_record fallback), и .mp3 (штатный
        // рекордер OxygenOS через RecordingLinker) — sherpa-onnx понимает только сырые
        // сэмплы, не контейнеры. decodeToMono уже умеет оба формата (тот же декодер, что
        // готовит приветствия) — раньше здесь был свой WAV-only парсер, падавший на .mp3
        // тем же образом, что и штатный WaveReader на стерео. Найдено живым тестом.
        val (shorts, sampleRate) = com.davnozdu.autoresponder.call.AudioConvert.decodeToMono(audioFile.absolutePath)
            ?: error("Не удалось разобрать аудиофайл")
        // Страховка на случай, если probeDurationSec выше вернул null (не смог оценить),
        // а фактическая длительность всё равно оказалась чрезмерной — уже после аллокации,
        // но хотя бы не даём скормить такой файл recognizer'у.
        if (sampleRate > 0 && shorts.size / sampleRate > MAX_DECODE_SEC) {
            error("Запись слишком длинная для локальной дешифровки (> ${MAX_DECODE_SEC / 60} мин)")
        }
        // Обрезка приветствия ПЕРЕД распознаванием — сам файл записи не меняется (см.
        // GreetingTrim), только то, что уходит в recognizer. Проверяем именно ФАКТ обрезки
        // (trimmed.size < shorts.size), а не просто "получилось коротко": короткая запись БЕЗ
        // найденного приветствия (сайдкара нет, или это правда короткое "алло") — не тот же
        // случай, что "нашли приветствие, а после него почти ничего" — только во втором
        // сообщаем именно "сообщения нет", в первом ведём себя как раньше (пробуем
        // распознать, что есть).
        val trimmed = com.davnozdu.autoresponder.call.GreetingTrim.trimForTranscription(
            audioFile.absolutePath, shorts, sampleRate)
        if (trimmed.size < shorts.size && com.davnozdu.autoresponder.call.GreetingTrim.isNegligible(trimmed, sampleRate)) {
            error("Сообщение не записано — похоже, звонивший положил трубку во время приветствия")
        }
        val rec = acquireRecognizer(ctx)
        try {
            val samples = FloatArray(trimmed.size) { trimmed[it] / 32768f }
            synchronized(decodeLock) {
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
            }
        } finally {
            finishDecode()
        }
    }
}
