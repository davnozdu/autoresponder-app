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
            val sz = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> { dataOff = body; dataLen = sz }
            }
            if (dataOff >= 0 && rate > 0) break
            pos = body + sz + (sz and 1) // чанки выровнены по 2 байта
        }
        if (dataOff < 0 || rate <= 0 || channels <= 0 || bits != 16) return null
        val n = minOf(dataLen, bytes.size - dataOff) / 2
        val out = ShortArray(n)
        val sb = ByteBuffer.wrap(bytes, dataOff, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        sb.get(out)
        return Pcm(out, rate, channels)
    }

    /** Декод сжатого аудио (mp3/aac/...) через MediaCodec в PCM16. */
    private fun decodeCompressed(path: String): Pcm? {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) { ex.release(); return null }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val out = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false; var sawOutputEos = false
        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                val chunk = ByteArray(info.size)
                buf.position(info.offset); buf.get(chunk)
                out.write(chunk)
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val bytes = out.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return Pcm(shorts, rate, channels)
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
        val outFrames = if (srcRate == OUT_RATE) frames
                        else ((frames.toLong() * OUT_RATE) / srcRate).toInt()
        val out = ShortArray(outFrames * 2)
        if (srcRate == OUT_RATE) {
            for (i in 0 until frames) { out[i * 2] = mono[i]; out[i * 2 + 1] = mono[i] }
        } else {
            val step = frames.toDouble() / outFrames
            for (i in 0 until outFrames) {
                val pos = i * step
                val i0 = pos.toInt()
                val frac = pos - i0
                val a = mono[i0]
                val b = if (i0 + 1 < frames) mono[i0 + 1] else a
                val v = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
                out[i * 2] = v; out[i * 2 + 1] = v
            }
        }
        return out
    }

    private fun writeLe16(outPath: String, samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        File(outPath).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }
}
