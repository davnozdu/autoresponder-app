package com.davnozdu.autoresponder.msgrec

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/** Shell-only check: tone -> isolated silence -> new tone on SAME recorder -> microphone. */
object MsgrSessionProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000)
        Looper.prepare()
        VoipOwnerAudio().use { ownerAudio ->
            ownerAudio.setSilent(true)
            check(ownerAudio.isMicMuted())
            val rate = VoipAudioInjector.RATE
            fun tone(hz: Int): ByteArray = ByteArray(rate * 2).also { pcm ->
                for (i in 0 until rate) {
                    val v = (sin(2 * PI * hz * i / rate) * 4000).toInt()
                    pcm[i * 2] = v.toByte(); pcm[i * 2 + 1] = (v shr 8).toByte()
                }
            }
            val played = AtomicInteger(-1)
            val injector = VoipAudioInjector(Process.myUid(), tone(997), true) { played.set(it) }
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, rate / 5 * 2)
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED)
                record.startRecording()
                fun read(frames: Int): ShortArray = ShortArray(frames).also { buf ->
                    var offset = 0
                    while (offset < frames) {
                        val count = record.read(buf, offset, frames - offset)
                        check(count > 0); offset += count
                    }
                }
                fun verifyTone(hz: Int) {
                    val buf = read(rate)
                    var s = 0.0; var c = 0.0; var energy = 0.0
                    for (i in rate / 2 until rate) {
                        val v = buf[i].toDouble()
                        s += v * sin(2 * PI * hz * i / rate)
                        c += v * cos(2 * PI * hz * i / rate)
                        energy += v * v
                    }
                    val n = rate / 2
                    val amplitude = 2 * sqrt(s * s + c * c) / n
                    val purity = if (energy == 0.0) 0.0 else 2 * (s * s + c * c) / (n * energy)
                    println("SESSION_PROBE tone=$hz amplitude=$amplitude purity=$purity")
                    check(amplitude in 3000.0..5000.0 && purity > 0.9)
                }
                read(rate / 2) // Drain initial setup silence.
                injector.play()
                verifyTone(997)
                // Drain queued tone; persistent mode must feed SILENCE, never room audio.
                read(rate * 2)
                val silent = read(rate / 2)
                check(played.get() == 0)
                check(silent.all { it == 0.toShort() }) { "microphone leaked between clips" }
                println("SESSION_PROBE SILENCE PASS")
                injector.replace(1, tone(1499))
                verifyTone(1499)
                injector.close()
                Thread.sleep(500)
                read(rate / 2)
                val route = record.routedDevice
                check(route != null && route.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
                println("SESSION_PROBE RESTORE PASS route=${route.type}")
            } finally {
                runCatching { record.stop() }; record.release(); injector.close()
            }
        }
        kotlin.system.exitProcess(0)
    }
}
