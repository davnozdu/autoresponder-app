package com.davnozdu.autoresponder.msgrec

import android.media.AudioManager
import android.os.IBinder
import android.os.Process
import java.io.Closeable

/** Session-owned output mute under shell, which holds MODIFY_PHONE_STATE. Android 16 silently
 * ignores the ordinary app's call-volume adjustments. Mute leaves each route's saved index
 * intact and also applies when switching between the phone, USB and Bluetooth headsets.
 * Hardware mic mute prevents headset sidetone. REMOTE_SUBMIX injection keeps working while
 * hardware microphones are muted (verified on this ROM). Closing IPC restores both, including
 * app death, without changing the user's saved stream indices.
 */
internal class VoipOwnerAudio : Closeable {
    private val serviceClass = Class.forName("android.media.IAudioService")
    private val service = Class.forName("android.media.IAudioService\$Stub")
        .getMethod("asInterface", IBinder::class.java).invoke(null,
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "audio"))
    private val adjust = serviceClass.methods.firstOrNull {
        it.name == "adjustStreamVolumeWithAttribution" && it.parameterCount == 5
    } ?: serviceClass.getMethod("adjustStreamVolume", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
    private var previousMute: Boolean? = null
    private val previousMicMute: Boolean
    private val setMic = serviceClass.methods.single {
        it.name == "setMicrophoneMute" && it.parameterCount in 3..4
    }
    private var closed = false
    private val worker = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(200)
                synchronized(this) {
                    if (!closed) {
                        if (!isMicMuted()) muteMic(true)
                        if (previousMute != null && !isMuted()) adjustMute(true)
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Exception) {
            System.err.println("msgrec owner mute monitor failed: ${t.message}")
        }
    }, "msgr-owner-mute").apply { isDaemon = true }

    init {
        check(Process.myUid() == 2000) { "shell audio owner required" }
        previousMicMute = isMicMuted()
        try {
            muteMic(true)
            check(isMicMuted()) { "hardware microphone was not muted" }
        } catch (t: Exception) {
            runCatching { muteMic(previousMicMute) }
            throw t
        }
        worker.start()
    }

    fun isMuted(): Boolean = serviceClass.getMethod("isStreamMute", Int::class.javaPrimitiveType)
        .invoke(service, AudioManager.STREAM_VOICE_CALL) as Boolean

    fun volume(): Int = serviceClass.getMethod("getStreamVolume", Int::class.javaPrimitiveType)
        .invoke(service, AudioManager.STREAM_VOICE_CALL) as Int

    fun isMicMuted(): Boolean = serviceClass.getMethod("isMicrophoneMuted").invoke(service) as Boolean

    private fun muteMic(muted: Boolean) {
        val args = mutableListOf<Any?>(muted, "com.android.shell", 0)
        if (setMic.parameterCount == 4) args.add(null)
        setMic.invoke(service, *args.toTypedArray())
    }

    private fun adjustMute(mute: Boolean) {
        val args = mutableListOf<Any?>(AudioManager.STREAM_VOICE_CALL,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0, "com.android.shell")
        if (adjust.parameterCount == 5) args.add(null)
        adjust.invoke(service, *args.toTypedArray())
    }

    @Synchronized fun setSilent(silent: Boolean) {
        check(!closed)
        if (silent) {
            if (previousMute == null) previousMute = isMuted()
            adjustMute(true)
            check(isMuted() && volume() == 0) { "call output was not silenced" }
        } else restore()
        println("msgrec owner audio silent=$silent muted=${isMuted()} volume=${volume()} micMuted=${isMicMuted()}")
    }

    private fun restore() {
        val saved = previousMute ?: return
        adjustMute(saved)
        check(isMuted() == saved) { "call output mute was not restored" }
        previousMute = null
    }

    override fun close() {
        worker.interrupt()
        synchronized(this) {
            closed = true
            try { restore() } finally {
                muteMic(previousMicMute)
                check(isMicMuted() == previousMicMute) { "hardware microphone was not restored" }
                println("msgrec owner audio restored muted=${isMuted()} volume=${volume()} micMuted=${isMicMuted()}")
            }
        }
        worker.join(1000)
    }
}
