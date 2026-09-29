package com.davnozdu.autoresponder.msgrec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Long-lived, idle-blocking shell process launched by the KSU module. Its AudioPolicy must exist
 * before the messenger creates the call track. MIC is captured under uid 2000, verified on this ROM;
 * the app's background UID receives digital silence even as a privileged foreground service.
 */
object MsgrShellHost {
    private const val SOCKET = "autoresp_msgr_capture"
    private const val RATE = VoipAudioPolicy.SAMPLE_RATE
    private const val DIR = "/sdcard/Music/Recordings/Messenger"

    // The APK this host was launched with (CLASSPATH). If the app later reports a different path it
    // has been updated and this host is stale, so it exits and the module relaunches it fresh.
    private val ownApk: String? = System.getenv("CLASSPATH")

    @JvmStatic
    fun main(args: Array<String>) {
        val appUid = args.firstOrNull()?.toIntOrNull() ?: return
        if (Looper.myLooper() == null) Looper.prepare()
        check(Process.myUid() == 2000) { "shell UID required" }
        cleanupOrphans()
        check(VoipAudioPolicy.arm()) { "could not arm VoIP AudioPolicy" }
        println("msgrec host ready uid=${Process.myUid()} appUid=$appUid apk=$ownApk")
        LocalServerSocket(SOCKET).use { server ->
            while (true) {
                server.accept().use { socket ->
                    if (socket.peerCredentials.uid == appUid) {
                        runCatching { handle(socket) }
                            .onFailure { System.err.println("msgrec session: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                }
            }
        }
    }

    /** A session killed mid-call (process death) leaves hidden temp files behind; drop them on start. */
    private fun cleanupOrphans() {
        runCatching {
            File(DIR).listFiles { f -> f.name.startsWith(".") &&
                (f.name.endsWith(".raw") || f.name.endsWith(".partial")) }
                ?.forEach { it.delete() }
        }
    }

    private fun handle(socket: LocalSocket) {
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        when (input.readUTF()) {
            "HELLO" -> {
                val appApk = runCatching { input.readUTF() }.getOrNull()
                val stale = ownApk != null && appApk != null && appApk != ownApk
                runCatching { output.writeUTF(if (stale) "RESTART" else "OK"); output.flush() }
                if (stale) {
                    println("msgrec host stale (app apk=$appApk, ours=$ownApk); exiting for restart")
                    System.exit(0)
                }
                return
            }
            "START" -> Unit
            else -> return
        }
        val label = input.readUTF()
        val startedAt = input.readLong()
        val session = Session(label, startedAt)
        session.start()
        output.writeUTF("READY")
        output.flush()
        var peer = ""
        try {
            if (input.readUTF() == "STOP") peer = input.readUTF()
        } finally {
            val result = session.finish(peer)
            println("msgrec saved ${result.path} ${result.durationMs}ms mic=${result.nearNonzero} far=${result.farNonzero}")
            runCatching {
                output.writeUTF("DONE")
                output.writeUTF(result.path)
                output.writeLong(result.durationMs)
                output.writeLong(result.nearNonzero)
                output.writeLong(result.farNonzero)
                output.flush()
            }
        }
    }

    private data class Result(val path: String, val durationMs: Long, val nearNonzero: Long, val farNonzero: Long)

    private class Session(private val label: String, private val startedAt: Long) {
        private lateinit var near: Reader
        private lateinit var far: Reader
        private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(startedAt))
        private val dir = File(DIR)

        fun start() {
            dir.mkdirs()
            val farRecord = VoipAudioPolicy.createSink() ?: error("far-party sink unavailable")
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val nearRecord = AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            check(nearRecord.state == AudioRecord.STATE_INITIALIZED) { "MIC not initialised" }
            far = Reader(farRecord, File(dir, ".$stamp.far.raw"), "far")
            near = Reader(nearRecord, File(dir, ".$stamp.near.raw"), "mic")
            far.start(); near.start()
            try {
                far.awaitStarted(); near.awaitStarted()
            } catch (error: Throwable) {
                far.finish(); near.finish()
                throw error
            }
        }

        fun finish(peer: String): Result {
            near.finish(); far.finish()
            val file = File(dir, "Messenger_${safe(label)}_${safe(peer.ifBlank { "Unknown" })}_$stamp.wav")
            val partial = File(dir, ".$stamp.wav.partial")
            val frames = mix(partial, near, far)
            check(partial.renameTo(file)) { "could not publish ${file.name}" }
            near.file.delete(); far.file.delete()
            return Result(file.absolutePath, frames * 1000 / RATE, near.nonzero, far.nonzero)
        }
    }

    private class Reader(val record: AudioRecord, val file: File, name: String) {
        @Volatile private var running = true
        @Volatile var frames = 0L
        @Volatile var nonzero = 0L
        @Volatile var firstFrameNanos = 0L
        @Volatile private var failure: Throwable? = null
        private val started = CountDownLatch(1)
        private val thread = Thread({ run() }, "msgrec-$name")

        fun start() = thread.start()
        fun awaitStarted() {
            check(started.await(5, TimeUnit.SECONDS)) { "audio source did not start" }
            failure?.let { throw IllegalStateException("audio source failed to start", it) }
        }

        private fun run() {
            val buf = ShortArray(2048)
            val bytes = ByteArray(buf.size * 2)
            try {
                file.outputStream().buffered().use { output ->
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "AudioRecord did not enter RECORDING"
                    }
                    started.countDown()
                    while (running) {
                        val n = record.read(buf, 0, buf.size)
                        if (n < 0) error("AudioRecord.read failed: $n")
                        if (n == 0) continue
                        if (firstFrameNanos == 0L) firstFrameNanos = System.nanoTime() - n * 1_000_000_000L / RATE
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            if (v != 0) nonzero++
                            bytes[i * 2] = v.toByte()
                            bytes[i * 2 + 1] = (v shr 8).toByte()
                        }
                        output.write(bytes, 0, n * 2)
                        frames += n
                    }
                }
            } catch (error: Throwable) {
                failure = error
                System.err.println("msgrec reader failed: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                started.countDown()
                runCatching { record.stop() }
                record.release()
            }
        }

        fun finish() {
            running = false
            thread.join(3000)
            if (thread.isAlive) {
                runCatching { record.stop() }
                thread.join(3000)
            }
            check(!thread.isAlive) { "audio reader did not stop" }
        }
    }

    private fun mix(outFile: File, near: Reader, far: Reader): Long {
        val base = minOf(near.firstFrameNanos.takeIf { it > 0 } ?: Long.MAX_VALUE,
            far.firstFrameNanos.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val nearOffset = if (base == Long.MAX_VALUE) 0L else
            ((near.firstFrameNanos - base).coerceAtLeast(0L) * RATE / 1_000_000_000L)
        val farOffset = if (base == Long.MAX_VALUE) 0L else
            ((far.firstFrameNanos - base).coerceAtLeast(0L) * RATE / 1_000_000_000L)
        val total = maxOf(nearOffset + near.frames, farOffset + far.frames)
        RandomAccessFile(outFile, "rw").use { out ->
            out.setLength(0)
            writeHeader(out, 0)
            RandomAccessFile(near.file, "r").use { nFile ->
                RandomAccessFile(far.file, "r").use { fFile ->
                    val nBuf = ByteArray(4096)
                    val fBuf = ByteArray(4096)
                    val mixed = ByteArray(4096)
                    var pos = 0L
                    while (pos < total) {
                        val count = minOf(2048L, total - pos).toInt()
                        nBuf.fill(0); fBuf.fill(0)
                        readAt(nFile, nBuf, pos - nearOffset, near.frames, count)
                        readAt(fFile, fBuf, pos - farOffset, far.frames, count)
                        for (i in 0 until count) {
                            val a = (nBuf[i * 2].toInt() and 255) or (nBuf[i * 2 + 1].toInt() shl 8)
                            val b = (fBuf[i * 2].toInt() and 255) or (fBuf[i * 2 + 1].toInt() shl 8)
                            val v = (a.toShort().toInt() + b.toShort().toInt()).coerceIn(-32768, 32767)
                            mixed[i * 2] = v.toByte()
                            mixed[i * 2 + 1] = (v shr 8).toByte()
                        }
                        out.write(mixed, 0, count * 2)
                        pos += count
                    }
                }
            }
            out.seek(0)
            writeHeader(out, (total * 2).toInt())
        }
        return total
    }

    private fun readAt(file: RandomAccessFile, buf: ByteArray, sourceFrame: Long, frames: Long, count: Int) {
        val first = sourceFrame.coerceAtLeast(0)
        val last = (sourceFrame + count).coerceAtMost(frames)
        if (last <= first) return
        val dst = (first - sourceFrame).toInt() * 2
        file.seek(first * 2)
        file.readFully(buf, dst, (last - first).toInt() * 2)
    }

    private fun writeHeader(out: RandomAccessFile, bytes: Int) {
        out.writeBytes("RIFF"); le32(out, 36 + bytes); out.writeBytes("WAVEfmt ")
        le32(out, 16); le16(out, 1); le16(out, 1); le32(out, RATE)
        le32(out, RATE * 2); le16(out, 2); le16(out, 16)
        out.writeBytes("data"); le32(out, bytes)
    }
    private fun le32(out: RandomAccessFile, v: Int) {
        for (i in 0..3) out.write((v ushr (i * 8)) and 255)
    }
    private fun le16(out: RandomAccessFile, v: Int) {
        out.write(v and 255); out.write((v ushr 8) and 255)
    }
    // Structural parts of the name stay English ("Messenger_<app>_"), but the caller name itself is
    // kept as-is — Cyrillic and all — because that is what makes a recording findable on disk
    // (по просьбе пользователя: «Мама Нидерланды» в имени файла — это нормально). Only characters
    // unsafe for a filename (spaces, punctuation, path separators) are replaced with underscores.
    private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        .trim('_', '.').take(48).ifEmpty { "Unknown" }
}
