package com.davnozdu.autoresponder.call

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Обрезка приветствия из записи ПЕРЕД транскрипцией — исходный файл записи никогда не
 * меняется, обрезка применяется только к тому, что скармливается в LocalTranscriber/
 * Transcriber. Если что-то пойдёт не так (сайдкара нет, эталон не нашёлся, оценка ниже
 * порога) — просто транскрибируем как раньше, без обрезки: безопасный отказ, а не угадывание.
 *
 * Приветствие ищется в записи кросс-корреляцией по огибающей громкости (RMS по 20мс-кадрам),
 * а не по времени начала play()/recstart(): у демона (answermachine.sh) обе команды
 * fire-and-forget — recstart отвечает "ok" сразу после запуска pal_record в фон, НЕ дожидаясь
 * реального открытия аудиопотока; то же самое play()/pal_inject. Точной привязки по
 * таймстемпам между "команда отправлена" и "сэмпл N записи — это именно этот момент" нет.
 * Огибающая, а не сырые сэмплы — на порядки дешевле по вычислениям на записи в минуты длиной
 * и устойчивее к разнице в усилении/шуме между инжектированным звуком и тем, что реально
 * забрал pal_record (микрофонный тракт, кодирование).
 *
 * Найденное окно НАРОЧНО сужается запасом с обеих сторон перед вырезанием — если клиент начал
 * говорить внахлёст с ещё звучащим приветствием, лучше оставить в транскрипте лишний обрывок
 * приветствия, чем потерять реальное слово клиента (см. обсуждение с пользователем).
 */
object GreetingTrim {
    private const val FRAME_MS = 20
    private const val MARGIN_MS = 350
    private const val MIN_CORRELATION = 0.5
    private const val GREET_RATE = 48000 // формат всех Greeting.prepare*() — см. AudioConvert.toRawPcm48kStereo
    // Меньше — не пытаемся оценивать: на паре кадров нормализованная корреляция ненадёжна
    // (слишком легко случайно "совпасть", особенно если оба отрезка попадают на "плоский"
    // участок ровной громкости — короткое окно почти не несёт различающей информации).
    // 25 кадров по 20мс = 500мс — абсолютный пол независимо от длины записи.
    private const val MIN_PREFIX_FRAMES = 25
    // isGreetingOnlyFragment не должна иметь права "подобрать" короткое удобное окно совпадения
    // где-то внутри записи — сравнивать нужно СУЩЕСТВЕННУЮ долю всей записи (не меньше этой
    // доли), иначе даже НЕ похожий на приветствие сигнал может случайно совпасть коротким
    // куском на общем "ровном" участке громкости. Найдено юнит-тестом (CI): короткое
    // непохожее сообщение ложно распозналось как обрывок приветствия именно на 10-кадровом
    // (200мс) окне внутри протяжённого ровного участка.
    private const val MIN_MATCH_FRACTION = 0.7
    // Сколько тишины/паузы перед началом приветствия готовы искать (ответ+IPC-задержка play()
    // редко больше пары секунд) — см. isGreetingOnlyFragment.
    private const val MAX_LEAD_SILENCE_MS = 5000
    /** После обрезки осталось меньше этого — считаем, что реального сообщения нет. См.
     *  isGreetingOnlyFragment/trimForTranscription. Найдено пользователем: "бывает, люди
     *  бросают трубку быстрее, чем успевает прочитаться приветствие" — тогда ЛЮБОЙ текст,
     *  который распознавание достанет из огрызка, — это в лучшем случае кусок самого
     *  приветствия, а не слова клиента; честнее явно сказать "сообщения нет". */
    const val MIN_CONTENT_MS = 500

    /** Путь к сайдкар-файлу — точной копии PCM, реально сыгранного в линию для ЭТОЙ записи
     *  (копируется в AnswerMachineService рядом с сохранением записи). Headerless raw PCM
     *  48000Гц/16бит/stereo — тот же формат, что все файлы Greeting.prepare*(). */
    fun sidecarPath(recordingPath: String): String = "$recordingPath.greet.pcm"

    /** Обрезает приветствие из уже декодированной [recording] (моно PCM, частота [recRate])
     *  для локальной транскрипции записи по пути [recordingPath]. Сайдкара нет (старая запись
     *  до этой фичи, или звонок без сохранённого эталона, или звонок вообще без приветствия) —
     *  возвращает [recording] без изменений. Никогда не бросает исключение. */
    fun trimForTranscription(recordingPath: String, recording: ShortArray, recRate: Int): ShortArray {
        val sidecar = File(sidecarPath(recordingPath))
        if (!sidecar.exists() || sidecar.length() < 4) return recording
        return try {
            val greetMono = downmixStereo(readRawPcm16(sidecar))
            // Сначала — не оборвал ли клиент звонок ДО конца приветствия (вся запись, кроме
            // небольшой паузы вначале, это и есть приветствие, "после" ничего нет). Проверяем
            // отдельно от обычного findExcludeWindow: там margin защищает границу с РЕАЛЬНЫМ
            // содержимым после приветствия, а здесь после приветствия ничего нет по построению —
            // нечего защищать, значит и огрызок приветствия по краям оставлять незачем.
            if (isGreetingOnlyFragment(recording, recRate, greetMono, GREET_RATE)) return ShortArray(0)
            applyExclude(recording, findExcludeWindow(recording, recRate, greetMono, GREET_RATE))
        } catch (e: Exception) { recording }
    }

    /** Для облачной транскрипции: если сайдкар есть и обрезка реально что-то находит —
     *  декодирует запись, обрезает, пишет ВРЕМЕННЫЙ WAV рядом с [audioFile] и возвращает его;
     *  вызывающий код (Transcriber) грузит его вместо оригинала и сам удаляет после отправки.
     *  null — обрезать нечего или не вышло, грузим исходный файл как раньше (без единого
     *  лишнего декодирования, если сайдкара нет вовсе — самый частый случай, старые записи).
     *  @throws IllegalStateException (через error()), если после обрезки реального сообщения
     *  не осталось (см. [isNegligible]) — короткое замыкание до похода в сеть, а не платный
     *  запрос ради огрызка приветствия. */
    fun trimmedCopyForUpload(audioFile: File): File? {
        val sidecar = File(sidecarPath(audioFile.absolutePath))
        if (!sidecar.exists() || sidecar.length() < 4) return null
        val (shorts, rate) = (try { AudioConvert.decodeToMono(audioFile.absolutePath) } catch (e: Exception) { null })
            ?: return null
        val trimmed = trimForTranscription(audioFile.absolutePath, shorts, rate)
        if (trimmed.size >= shorts.size) return null // ничего не нашли — грузим оригинал как раньше
        if (isNegligible(trimmed, rate)) {
            error("Сообщение не записано — похоже, звонивший положил трубку во время приветствия")
        }
        return try {
            val tmp = File.createTempFile("greet_trim_", ".wav", audioFile.parentFile)
            AudioConvert.writeWavMono16(tmp.absolutePath, trimmed, rate)
            tmp
        } catch (e: Exception) { null }
    }

    private fun readRawPcm16(f: File): ShortArray {
        val bytes = f.readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun downmixStereo(interleaved: ShortArray): ShortArray {
        val frames = interleaved.size / 2
        val out = ShortArray(frames)
        for (i in 0 until frames) out[i] = ((interleaved[i * 2].toInt() + interleaved[i * 2 + 1].toInt()) / 2).toShort()
        return out
    }

    /** Находит окно в [recording] (индексы сэмплов) для исключения из транскрипции, или null,
     *  если уверенного совпадения не нашлось — тогда вызывающий код просто не обрезает.
     *  Возвращаемое окно уже сужено на MARGIN_MS с каждой стороны относительно найденного
     *  совпадения (см. комментарий класса — недорезаем, а не перерезаем).
     *
     *  Только для случая "запись длиннее эталона" (обычное "приветствие + сообщение") — ищет
     *  окно скольжением по всей записи. Случай "запись короче эталона" (клиент положил трубку
     *  до конца приветствия) обрабатывает отдельная [isGreetingOnlyFragment] — там margin с
     *  конца не нужен (после приветствия по построению ничего нет, нечего защищать). */
    fun findExcludeWindow(recording: ShortArray, recRate: Int, greetMono: ShortArray, greetRate: Int): IntRange? {
        if (recording.isEmpty() || greetMono.isEmpty() || recRate <= 0 || greetRate <= 0) return null
        val greetAtRecRate = if (greetRate == recRate) greetMono else AudioConvert.resampleMono(greetMono, greetRate, recRate)
        if (greetAtRecRate.isEmpty()) return null

        val frameLen = (recRate * FRAME_MS / 1000).coerceAtLeast(1)
        val recEnv = envelope(recording, frameLen)
        val greetEnv = envelope(greetAtRecRate, frameLen)
        if (recEnv.isEmpty() || greetEnv.isEmpty() || greetEnv.size >= recEnv.size) return null

        val greetNorm = normalize(greetEnv)
        var bestOffset = -1
        var bestScore = Double.NEGATIVE_INFINITY
        val lastOffset = recEnv.size - greetEnv.size
        for (offset in 0..lastOffset) {
            val segment = recEnv.copyOfRange(offset, offset + greetEnv.size)
            val score = normalizedCorrelation(normalize(segment), greetNorm)
            if (score > bestScore) { bestScore = score; bestOffset = offset }
        }
        if (bestOffset < 0 || bestScore < MIN_CORRELATION) return null

        val startSample = bestOffset * frameLen
        val endSample = (startSample + greetEnv.size * frameLen).coerceAtMost(recording.size)
        val marginSamples = recRate * MARGIN_MS / 1000
        val shrunkStart = (startSample + marginSamples).coerceAtMost(endSample)
        val shrunkEnd = (endSample - marginSamples).coerceAtLeast(shrunkStart)
        if (shrunkEnd <= shrunkStart) return null
        return shrunkStart until shrunkEnd
    }

    /** true, если ВСЯ [recording] (не считая небольшой паузы вначале) — это фрагмент
     *  [greetMono] от его собственного начала: клиент положил трубку до/во время приветствия,
     *  реального сообщения, скорее всего, не было вовсе. Ищет, с какой паузой в начале (до
     *  MAX_LEAD_SILENCE_MS — ответ+задержка play() редко больше пары секунд) остаток записи до
     *  самого её конца совпадает с началом эталона той же длины; в отличие от
     *  [findExcludeWindow] здесь нет margin с конца — "после" защищать нечего, запись просто
     *  обрывается. Естественная граница применимости: если [recording] длиннее [greetMono],
     *  это уже не "оборвали на приветствии" — обычный случай, см. [findExcludeWindow]. */
    fun isGreetingOnlyFragment(recording: ShortArray, recRate: Int, greetMono: ShortArray, greetRate: Int): Boolean {
        if (recording.isEmpty() || greetMono.isEmpty() || recRate <= 0 || greetRate <= 0) return false
        val greetAtRecRate = if (greetRate == recRate) greetMono else AudioConvert.resampleMono(greetMono, greetRate, recRate)
        if (greetAtRecRate.isEmpty()) return false

        val frameLen = (recRate * FRAME_MS / 1000).coerceAtLeast(1)
        val recEnv = envelope(recording, frameLen)
        val greetEnv = envelope(greetAtRecRate, frameLen)
        if (recEnv.isEmpty() || greetEnv.isEmpty() || recEnv.size > greetEnv.size) return false

        val minMatchLen = (recEnv.size * MIN_MATCH_FRACTION).toInt().coerceAtLeast(MIN_PREFIX_FRAMES)
        val maxLeadFrames = (recEnv.size - minMatchLen).coerceAtMost(MAX_LEAD_SILENCE_MS / FRAME_MS)
        if (maxLeadFrames < 0) return false
        var bestScore = Double.NEGATIVE_INFINITY
        for (offset in 0..maxLeadFrames) {
            val matchLen = recEnv.size - offset
            val segment = recEnv.copyOfRange(offset, offset + matchLen)
            val prefix = greetEnv.copyOfRange(0, matchLen)
            val score = normalizedCorrelation(normalize(segment), normalize(prefix))
            if (score > bestScore) bestScore = score
        }
        return bestScore >= MIN_CORRELATION
    }

    /** Осталось ли после обрезки хоть что-то похожее на реальное сообщение, а не огрызок
     *  приветствия/тишина. См. [MIN_CONTENT_MS]. */
    fun isNegligible(samples: ShortArray, rate: Int): Boolean =
        rate <= 0 || samples.size < rate.toLong() * MIN_CONTENT_MS / 1000

    /** Вырезает [exclude] из [recording] — новый массив, оригинал не меняется. null — вернуть
     *  запись как есть. */
    fun applyExclude(recording: ShortArray, exclude: IntRange?): ShortArray {
        if (exclude == null) return recording
        val start = exclude.first.coerceIn(0, recording.size)
        val end = (exclude.last + 1).coerceIn(start, recording.size)
        if (start >= end) return recording
        val out = ShortArray(recording.size - (end - start))
        System.arraycopy(recording, 0, out, 0, start)
        System.arraycopy(recording, end, out, start, recording.size - end)
        return out
    }

    private fun envelope(samples: ShortArray, frameLen: Int): DoubleArray {
        val frames = samples.size / frameLen
        val out = DoubleArray(frames)
        for (i in 0 until frames) {
            var sumSq = 0.0
            val base = i * frameLen
            for (j in 0 until frameLen) {
                val v = samples[base + j].toDouble()
                sumSq += v * v
            }
            out[i] = sqrt(sumSq / frameLen)
        }
        return out
    }

    /** Z-score: нулевое среднее, единичная дисперсия — корреляция устойчива к разнице в
     *  усилении между эталоном и записью. Почти тишина (нулевая дисперсия) — нулевой вектор,
     *  ему ни с чем не набрать высокий скор, и это правильно (не считаем тишину "похожей"). */
    private fun normalize(env: DoubleArray): DoubleArray {
        if (env.isEmpty()) return env
        val mean = env.average()
        var variance = 0.0
        for (v in env) variance += (v - mean) * (v - mean)
        variance /= env.size
        val std = sqrt(variance)
        if (std < 1e-9) return DoubleArray(env.size)
        return DoubleArray(env.size) { (env[it] - mean) / std }
    }

    private fun normalizedCorrelation(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum / a.size
    }
}
