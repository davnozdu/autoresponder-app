/*
 * Messenger (VoIP) capture feasibility probe for AutoResponder.
 *
 * Increment 1 of the messenger-recording integration: a standalone entry point launched by
 * app_process under a privileged user (root, via the KSU daemon — or shell for a manual test).
 * It arms the far-party AudioPolicy, opens the near-side mic, records both to WAV, and reports
 * whether the far side carried real signal — the one thing that decides whether the whole feature
 * is viable on this device/ROM (OnePlus 15, OxygenOS/Android 16, region IN).
 *
 * The capture mechanism ([VoipAudioPolicy], [BypassedAudioRecord]) is derived from CallVault
 * (GPL-3.0 + §7); see LICENSE and NOTICE.md.
 *
 * Run (as root, package installed):
 *   CLASSPATH=$(pm path com.davnozdu.autoresponder | sed 's/package://') \
 *     app_process /system/bin com.davnozdu.autoresponder.msgrec.MsgrCaptureProbe [recSec] [armWaitSec] [outDir]
 * Then place a WhatsApp/Telegram call within armWaitSec seconds.
 *
 *  Copyright (C) 2026-present davnozdu — GPL-3.0-or-later with §7 additional terms.
 */

package com.davnozdu.autoresponder.msgrec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

object MsgrCaptureProbe {
    private const val TAG = "AR:MsgrProbe"
    private const val RATE = VoipAudioPolicy.SAMPLE_RATE // 48 kHz

    @JvmStatic
    fun main(args: Array<String>) {
        val recSec = args.getOrNull(0)?.toIntOrNull() ?: 20
        val armWaitSec = args.getOrNull(1)?.toIntOrNull() ?: 6
        val outDir = args.getOrNull(2) ?: "/sdcard/Music/Recordings/Messenger"

        // AudioRecord/AudioPolicy want a Looper on the thread that creates them.
        if (Looper.myLooper() == null) Looper.prepare()

        say("probe start: rec=${recSec}s armWait=${armWaitSec}s out=$outDir uid=${android.os.Process.myUid()}")
        File(outDir).mkdirs()

        // 1) Arm the far-party policy BEFORE the call's audio track is created.
        val armed = VoipAudioPolicy.arm()
        say("armed=$armed")
        if (!armed) {
            say("FAIL: could not arm policy — CAPTURE_VOICE_COMMUNICATION_OUTPUT missing for this uid, or OEM removed the API")
            return
        }

        say("Now place your WhatsApp/Telegram call — waiting ${armWaitSec}s...")
        Thread.sleep(armWaitSec * 1000L)

        // 2) Far side (peer) — the loopback sink; Near side (you) — plain MIC.
        val far = VoipAudioPolicy.createSink()
        say("far sink: ${if (far == null) "NULL (not created)" else "state=${far.state}"}")

        val nearBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)
        val near = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, nearBuf * 2,
            )
        }.onFailure { say("near mic create failed: ${it.message}") }.getOrNull()
        say("near mic: ${if (near == null) "NULL" else "state=${near.state}"}")

        val farFile = File(outDir, "probe-far.wav")
        val nearFile = File(outDir, "probe-near.wav")
        val farStat = if (far != null && far.state == AudioRecord.STATE_INITIALIZED)
            recordTo(far, farFile, recSec, "far") else null
        val nearStat = if (near != null && near.state == AudioRecord.STATE_INITIALIZED)
            recordTo(near, nearFile, recSec, "near") else null

        // Both readers run for recSec in their own threads; wait them out.
        farStat?.join(); nearStat?.join()

        runCatching { far?.release() }
        runCatching { near?.release() }
        VoipAudioPolicy.disarm()

        say("==== RESULT ====")
        report("far  (peer)", farFile, farStat)
        report("near (you) ", nearFile, nearStat)
        val farOk = farStat != null && farStat.maxAbs > 200
        say(if (farOk)
            "VERDICT: FAR side captured real audio -> mechanism WORKS on this device."
        else
            "VERDICT: far side is missing or silent -> loopback-render did not capture the peer here.")
    }

    /** A reader thread that writes 16-bit mono PCM into a WAV and tracks peak amplitude + bytes. */
    private class Stat(val label: String) : Thread("msgrec-$label") {
        @Volatile var bytes = 0L
        @Volatile var maxAbs = 0
        lateinit var body: () -> Unit
        override fun run() = body()
    }

    private fun recordTo(rec: AudioRecord, file: File, seconds: Int, name: String): Stat {
        val stat = Stat(name)
        stat.body = {
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            writeWavHeader(raf, 0) // placeholder, patched at the end
            val buf = ByteArray(4096)
            runCatching { rec.startRecording() }.onFailure { say("$name startRecording failed: ${it.message}") }
            val until = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < until) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(5); continue }
                raf.write(buf, 0, n)
                stat.bytes += n
                var i = 0
                while (i + 1 < n) {
                    val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                    val a = abs(s.toShort().toInt())
                    if (a > stat.maxAbs) stat.maxAbs = a
                    i += 2
                }
            }
            runCatching { rec.stop() }
            patchWavSizes(raf, stat.bytes)
            raf.close()
        }
        stat.start()
        return stat
    }

    private fun report(label: String, file: File, stat: Stat?) {
        if (stat == null) { say("$label : not recorded"); return }
        val secs = stat.bytes.toDouble() / (RATE * 2)
        say("$label : ${stat.bytes} bytes (~${"%.1f".format(secs)}s), peak=${stat.maxAbs} -> ${file.absolutePath}")
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val ch = 1; val bits = 16; val byteRate = RATE * ch * bits / 8
        raf.write("RIFF".toByteArray()); writeLE(raf, 36 + dataLen)
        raf.write("WAVE".toByteArray()); raf.write("fmt ".toByteArray()); writeLE(raf, 16)
        writeLE16(raf, 1); writeLE16(raf, ch); writeLE(raf, RATE); writeLE(raf, byteRate)
        writeLE16(raf, ch * bits / 8); writeLE16(raf, bits)
        raf.write("data".toByteArray()); writeLE(raf, dataLen)
    }

    private fun patchWavSizes(raf: RandomAccessFile, dataLen: Long) {
        raf.seek(4); writeLE(raf, (36 + dataLen).toInt())
        raf.seek(40); writeLE(raf, dataLen.toInt())
    }

    private fun writeLE(raf: RandomAccessFile, v: Int) =
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))

    private fun writeLE16(raf: RandomAccessFile, v: Int) =
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))

    private fun say(m: String) {
        println("[$TAG] $m")
        runCatching { android.util.Log.i(TAG, m) }
    }
}
