package com.davnozdu.autoresponder.msgrec

import android.media.AudioRecordingConfiguration
import android.os.IBinder
import android.os.SystemClock

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
    }
}
