package com.davnozdu.autoresponder.msgrec

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Looper
import android.os.Process
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Shell-only hardware check: discard owner output, keep far capture, restore SAME player. */
object MsgrOwnerOutputProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000)
        Looper.prepare()
        check(VoipAudioPolicy.arm())
        val sink = checkNotNull(VoipAudioPolicy.createSink())
        val owner = VoipOwnerAudio()
        var track: AudioTrack? = null
        var writer: Thread? = null
        try {
            sink.startRecording()
            owner.setSilent(true)
            @Suppress("DEPRECATION")
            val player = AudioTrack(0, 16_000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, 16_000, AudioTrack.MODE_STREAM)
            track = player
            val pcm = ByteArray(32_000)
            for (i in 0 until 16_000) {
                val value = (sin(2 * PI * 997 * i / 16_000) * 4000).toInt()
                pcm[2 * i] = value.toByte(); pcm[2 * i + 1] = (value shr 8).toByte()
            }
            player.play()
            writer = Thread({
                repeat(20) { if (!Thread.currentThread().isInterrupted) player.write(pcm, 0, pcm.size) }
            }, "owner-output-probe-writer").apply { isDaemon = true; start() }
            val samples = ShortArray(48_000)
            var offset = 0
            while (offset < samples.size) {
                val n = sink.read(samples, offset, samples.size - offset)
                check(n > 0); offset += n
            }
            val rms = sqrt(samples.drop(24_000).sumOf { it.toDouble() * it } / 24_000)
            check(rms > 1000) { "far capture was muted: $rms" }
            check(player.routedDevice?.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                "owner output still physical: ${player.routedDevice?.type}"
            }
            println("OWNER_OUTPUT_PROBE SILENT PASS virtual-only, farRms=$rms")
            player.setVolume(0f) // Keep the diagnostic tone inaudible during handoff.
            owner.setSilent(false)
            val deadline = System.currentTimeMillis() + 3000
            while (player.routedDevice?.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX &&
                System.currentTimeMillis() < deadline) Thread.sleep(50)
            check(player.routedDevice != null && player.routedDevice?.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
            println("OWNER_OUTPUT_PROBE RESTORE PASS route=${player.routedDevice?.type}")
        } finally {
            track?.setVolume(0f)
            writer?.interrupt()
            track?.let { runCatching { it.stop() } }
            writer?.join(1000)
            track?.release()
            owner.close()
            runCatching { sink.stop() }; sink.release()
            VoipAudioPolicy.disarm()
        }
        kotlin.system.exitProcess(0)
    }
}
