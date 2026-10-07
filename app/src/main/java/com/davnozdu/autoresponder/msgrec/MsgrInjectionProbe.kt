package com.davnozdu.autoresponder.msgrec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.os.Process
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** app_process shell-only hardware check. No calls are answered and no files are recorded. */
object MsgrInjectionProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000)
        Looper.prepare()
        val rate = VoipAudioInjector.RATE
        val pcm = ByteArray(rate * 2 * 4)
        for (i in 0 until pcm.size / 2) {
            val v = (sin(2 * PI * 997 * i / rate) * 4000).toInt()
            pcm[i * 2] = v.toByte(); pcm[i * 2 + 1] = (v shr 8).toByte()
        }
        val injector = VoipAudioInjector(Process.myUid(), pcm)
        var record: AudioRecord? = null
        try {
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, rate / 5 * 2)
            record = r
            check(r.state == AudioRecord.STATE_INITIALIZED)
            injector.play()
            r.startRecording()
            val buf = ShortArray(rate)
            var count = 0
            while (count < buf.size) {
                val n = r.read(buf, count, buf.size - count)
                check(n > 0) { "record read: $n" }; count += n
            }
            // Discard initial zeros while the route starts. Quadrature is phase independent.
            var s = 0.0; var c = 0.0; var energy = 0.0
            for (i in rate / 2 until rate) {
                val v = buf[i].toDouble()
                s += v * sin(2 * PI * 997 * i / rate)
                c += v * cos(2 * PI * 997 * i / rate)
                energy += v * v
            }
            val n = rate / 2
            val amplitude = 2 * sqrt(s * s + c * c) / n
            val purity = if (energy == 0.0) 0.0 else 2 * (s * s + c * c) / (n * energy)
            println("INJECTION_PROBE amplitude=$amplitude purity=$purity peak=${buf.maxOf { kotlin.math.abs(it.toInt()) }}")
            check(amplitude in 3000.0..5000.0 && purity > 0.9) { "injected tone did not reach AudioRecord" }
            println("INJECTION_PROBE PASS: UID-scoped audio replacement works")
        } finally {
            record?.let { runCatching { it.stop() }; it.release() }
            injector.close()
            println("INJECTION_PROBE policy removed")
        }
    }
}
