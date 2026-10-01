package com.davnozdu.autoresponder.call

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Приводит любой аудиофайл к RAW PCM 48000 Гц / 16 бит / stereo — именно этот формат ждёт
 * `pal_inject` (headerless PCM). Нужен для двух источников приветствия: WAV от TTS и файла,
 * загруженного пользователем (mp3/m4a/wav и т.п.).
 *
 * Файлы приветствия короткие (секунды), поэтому грузим целиком в память — так проще и
 * надёжнее, чем потоковая обработка.
 */
object AudioConvert {
    private const val OUT_RATE = 48000
    // In-memory transcription on a 512 MB Java heap needs a hard PCM bound.
    private const val MAX_DECODED_SAMPLES = 50_000_000 // 100 MB PCM16; ~8.5 min at 48 kHz stereo

    /** @return true, если [outPath] записан как PCM 48к/16/stereo. */
    fun toRawPcm48kStereo(inPath: String, outPath: String): Boolean {
        val pcm = try {
            if (isWav(inPath)) decodeWav16(inPath) else decodeCompressed(inPath)
        } catch (e: Exception) { null } ?: return false
        return try {
            val stereo48 = resampleToStereo48(pcm.samples, pcm.rate, pcm.channels)
            writeLe16(outPath, stereo48)
            true
        } catch (e: Exception) { false }
    }

    /** Декодирует ЛЮБОЙ формат записи (wav/mp3/aac/...) в моно PCM16, без ресемпла в 48к —
     *  для дешифровки речи, а не для инъекции в линию. Записи автоответчика бывают и .wav
     *  (свой pal_record fallback), и .mp3 (штатный рекордер OxygenOS, через RecordingLinker) —
     *  локальный распознаватель (LocalTranscriber) понимает только сырые сэмплы, не контейнеры,
     *  поэтому оба пути должны сюда попадать одинаково, как уже умеет toRawPcm48kStereo выше.
     *  @return (сэмплы, частота) или null, если декодировать не удалось. */
    fun decodeToMono(path: String): Pair<ShortArray, Int>? {
        // A transcription may be minutes long; the greeting decoder's fixed 20s deadline
        // would silently reject a valid recording on a slower device.
        val duration = probeDurationSec(path)
        val decodeTimeoutMs = if (duration != null && duration.isFinite() && duration > 0)
            (duration * 250).toLong().coerceIn(20_000L, 180_000L) else 60_000L
        val wav = isWav(path)
        if (wav && File(path).length() > MAX_DECODED_SAMPLES * 2L + 1_048_576L) return null
        val pcm = try {
            if (wav) decodeWav16(path) else decodeCompressed(path, decodeTimeoutMs)
        } catch (e: Exception) { null } ?: return null
        if (pcm.channels <= 1) return pcm.samples to pcm.rate
        val frames = pcm.samples.size / pcm.channels
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until pcm.channels) acc += pcm.samples[i * pcm.channels + c]
            mono[i] = (acc / pcm.channels).toShort()
        }
        return mono to pcm.rate
    }

    /** Оценивает длительность файла по одному заголовку/метаданным контейнера, БЕЗ полного
     *  декодирования — decodeWav16 читает файл целиком (File.readBytes()), а decodeCompressed
     *  копит весь поток в ByteArrayOutputStream, поэтому лимит длительности, проверенный
     *  ПОСЛЕ decodeToMono(), уже бесполезен против OOM на самом декодировании битого/очень
     *  длинного файла — сначала выделяется вся память, потом проверяется допустимость.
     *  Найдено повторным аудитом. @return секунды, или null, если оценить не удалось (тогда
     *  вызывающий код сам решает политику для неизвестной длительности). */
    fun probeDurationSec(path: String): Double? = try {
        if (isWav(path)) probeWavDurationSec(path) else probeCompressedDurationSec(path)
    } catch (e: Exception) { null }

    private fun probeWavDurationSec(path: String): Double? {
        RandomAccessFile(path, "r").use { f ->
            val len = f.length()
            if (len < 44) return null
            // 1МБ с запасом покрывает любой реалистичный заголовок (fmt+доп. чанки метаданных
            // до data) — не читаем файл целиком ради одной оценки длительности.
            val head = ByteArray(minOf(len, 1_048_576L).toInt())
            f.readFully(head)
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var rate = 0; var channels = 0; var bits = 0; var dataLen = -1L
            while (pos + 8 <= head.size) {
                val id = String(head, pos, 4)
                val sz = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
                val body = pos + 8
                if (sz > head.size - body && id != "data") break // чанк обрезан нашим окном — дальше не парсим
                when (id) {
                    "fmt " -> if (body + 16 <= head.size) {
                        channels = bb.getShort(body + 2).toInt()
                        rate = bb.getInt(body + 4)
                        bits = bb.getShort(body + 14).toInt()
                    }
                    // sz у "data" иногда 0/недостоверен на потоковой записи — реальный размер
                    // тогда оцениваем по факту файла (len - body), не по заявленному в чанке.
                    "data" -> dataLen = if (sz in 1..(len - body)) sz else (len - body)
                }
                if (dataLen >= 0 && rate > 0) break
                pos = body + sz.toInt() + (sz.toInt() and 1)
            }
            if (rate <= 0 || channels <= 0 || bits != 16 || dataLen < 0) return null
            val bytesPerSec = rate.toLong() * channels * (bits / 8)
            if (bytesPerSec <= 0) return null
            return dataLen.toDouble() / bytesPerSec
        }
    }

    private fun probeCompressedDurationSec(path: String): Double? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                return if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else null
            }
            null
        } catch (e: Exception) { null } finally { runCatching { ex.release() } }
    }

    private class Pcm(val samples: ShortArray, val rate: Int, val channels: Int)

    private fun isWav(path: String): Boolean = try {
        RandomAccessFile(path, "r").use { f ->
            val b = ByteArray(12); if (f.read(b) < 12) return false
            String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE"
        }
    } catch (e: Exception) { false }

    /** Разбор WAV PCM16 (типичный выход Android TTS). Неподдерживаемый WAV → null (уйдём в codec). */
    private fun decodeWav16(path: String): Pcm? {
        val bytes = File(path).readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Ищем чанки fmt и data (могут быть не сразу после заголовка).
        var pos = 12
        var rate = 0; var channels = 0; var bits = 0; var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            // Unsigned: signed Int для повреждённого/враждебного chunk size (напр. -8) даёт
            // pos = body + sz + (sz and 1) == pos — цикл не двигается и виснет на рабочем
            // потоке звонка без таймаута. Границу проверяем ДО арифметики, а не после.
            val sz = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (sz > bytes.size - body) break // усечённый/битый chunk — дальше не парсим
            when (id) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> { dataOff = body; dataLen = sz.toInt() }
            }
            if (dataOff >= 0 && rate > 0) break
            pos = body + sz.toInt() + (sz.toInt() and 1) // чанки выровнены по 2 байта; body+8 гарантирует рост даже при sz=0
        }
        if (dataOff < 0 || rate <= 0 || channels <= 0 || bits != 16) return null
        val n = minOf(dataLen, bytes.size - dataOff) / 2
        val out = ShortArray(n)
        val sb = ByteBuffer.wrap(bytes, dataOff, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        sb.get(out)
        return Pcm(out, rate, channels)
    }

    /** Декод сжатого аудио (mp3/aac/...) через MediaCodec в PCM16.
     *  @return null при ошибке формата ИЛИ если не успели дойти до EOS за дедлайн — частичный
     *  декод битого/подвисшего файла не должен маскироваться под валидное приветствие/дешифровку. */
    private fun decodeCompressed(path: String, timeoutMs: Long = 20_000L): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (rate <= 0 || channels <= 0) return null
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var samples = ShortArray(64 * 1024)
            var sampleCount = 0
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false; var sawOutputEos = false
            // Greeting keeps a 20s deadline; transcription scales it with recording length.
            // A corrupt file must never keep the decoder in this loop indefinitely.
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            while (!sawOutputEos && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!sawInputEos) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf = c.getOutputBuffer(outIdx)!!
                    if (info.size % 2 != 0) return null // PCM16 must contain whole samples
                    val count = info.size / 2
                    val needed = sampleCount.toLong() + count
                    if (needed > MAX_DECODED_SAMPLES) return null
                    if (needed > samples.size) {
                        val capacity = maxOf(needed.toInt(), samples.size * 2)
                            .coerceAtMost(MAX_DECODED_SAMPLES)
                        samples = samples.copyOf(capacity)
                    }
                    val pcm = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    pcm.position(info.offset)
                    pcm.limit(info.offset + info.size)
                    pcm.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        .get(samples, sampleCount, count)
                    sampleCount += count
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
            // Найдено аудитом: раньше таймаут по дедлайну молча возвращал накопленный кусок
            // как успешный результат — битый/подвисший файл превращался в урезанное
            // приветствие или неполную расшифровку без явной ошибки.
            if (!sawOutputEos) return null
            return Pcm(samples.copyOf(sampleCount), rate, channels)
        } finally {
            // Найдено аудитом: исключение в setDataSource/configure/start/чтении буфера раньше
            // пропускало освобождение нативных объектов целиком (release вызывался только в
            // конце функции при успехе) — накопление кодек-дескрипторов при повторных попытках
            // импортировать неподдерживаемый файл.
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    /** Линейный ресемпл до 48к + приведение к stereo. Для речи линейной интерполяции хватает. */
    private fun resampleToStereo48(src: ShortArray, srcRate: Int, srcCh: Int): ShortArray {
        // сначала в моно-фреймы исходного рейта
        val frames = if (srcCh <= 1) src.size else src.size / srcCh
        val mono = ShortArray(frames)
        if (srcCh <= 1) {
            System.arraycopy(src, 0, mono, 0, frames)
        } else {
            for (i in 0 until frames) {
                var acc = 0
                for (c in 0 until srcCh) acc += src[i * srcCh + c]
                mono[i] = (acc / srcCh).toShort()
            }
        }
        val monoOut = resampleMono(mono, srcRate, OUT_RATE)
        val out = ShortArray(monoOut.size * 2)
        for (i in monoOut.indices) { out[i * 2] = monoOut[i]; out[i * 2 + 1] = monoOut[i] }
        return out
    }

    /** Линейный ресемпл моно-сигнала на произвольную целевую частоту. Публичный — переиспользуется
     *  GreetingTrim, чтобы выровнять частоту записи и эталонного PCM приветствия перед
     *  кросс-корреляцией (они не обязаны совпадать: приветствие всегда 48к, а запись — как её
     *  отдал decodeToMono, частота источника). */
    fun resampleMono(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate || src.isEmpty()) return src
        val outFrames = ((src.size.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames)
        val step = src.size.toDouble() / outFrames
        for (i in 0 until outFrames) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val frac = pos - i0
            val a = src[i0]
            val b = if (i0 + 1 < src.size) src[i0 + 1] else a
            out[i] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun writeLe16(outPath: String, samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        File(outPath).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    /** Записывает PCM16 моно как обычный WAV (44-байтный заголовок) — нужен там, где на выходе
     *  должен быть настоящий контейнер, а не headerless raw (в отличие от [writeLe16]),
     *  например для загрузки обрезанного под транскрипцию аудио в облачный API. */
    fun writeWavMono16(outPath: String, samples: ShortArray, rate: Int) {
        val dataLen = samples.size * 2
        val bytes = ByteArray(44 + dataLen)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in samples) bb.putShort(s)
        File(outPath).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }
}
