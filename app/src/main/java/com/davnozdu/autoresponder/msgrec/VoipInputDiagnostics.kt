package com.davnozdu.autoresponder.msgrec

import android.media.AudioRecordingConfiguration
import android.os.IBinder
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit

/** Short, metadata-only snapshots during greeting sessions. No idle polling or audio reads. */
internal object VoipInputDiagnostics {
    fun snapshot(stage: String, uid: Int) {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "audio") as IBinder
            val service = Class.forName("android.media.IAudioService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            @Suppress("UNCHECKED_CAST")
            val configs = Class.forName("android.media.IAudioService")
                .getMethod("getActiveRecordingConfigurations").invoke(service) as List<AudioRecordingConfiguration>
            val lines = configs.map { c ->
                val clientUid = c.javaClass.getMethod("getClientUid").invoke(c)
                val source = c.javaClass.getMethod("getClientAudioSource").invoke(c)
                val silenced = c.javaClass.getMethod("isClientSilenced").invoke(c)
                "uid=$clientUid source=$source device=${c.audioDevice?.type} silenced=$silenced"
            }
            println("msgrec inject input stage=$stage target=$uid elapsed=${SystemClock.elapsedRealtime()} " +
                "recorders=${lines.joinToString("; ").ifEmpty { "none" }}")
        }.onFailure { println("msgrec inject input stage=$stage unavailable=${it.javaClass.simpleName}:${it.message}") }
        // Keep only the latest bounded set of snapshots, including native recorders that
        // may not appear in AudioRecordingConfiguration. Runs during this session only.
        runCatching {
            val file = File("/data/local/tmp/autoresp-msgr-policy-$stage.txt")
            val process = ProcessBuilder("dumpsys", "media.audio_policy")
                .redirectErrorStream(true).redirectOutput(file).start()
            try {
                check(process.waitFor(2, TimeUnit.SECONDS)) { "policy dump timed out" }
                println("msgrec inject policy stage=$stage target=$uid path=${file.absolutePath}")
            } finally { process.destroy() }
        }.onFailure { println("msgrec inject policy stage=$stage unavailable=${it.message}") }
    }
}
