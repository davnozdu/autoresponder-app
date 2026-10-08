package com.davnozdu.autoresponder.msgrec

import android.media.AudioManager
import android.os.IBinder
import android.os.Process
import java.io.Closeable

/** Session-owned output mute under shell, which holds MODIFY_PHONE_STATE. Android 16 silently
 * ignores the ordinary app's call-volume adjustments. Mute leaves each route's saved index
 * intact and also applies when switching between the phone, USB and Bluetooth headsets.
 * The microphone stays isolated by VoipAudioInjector; global mic mute would silence greeting
 * injection too. Closing the IPC session restores both independently, including app death.
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
    private var closed = false
    private val worker = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(200)
                synchronized(this) {
                    if (previousMute != null && !closed && !isMuted()) adjustMute(true)
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Exception) {
            System.err.println("msgrec owner mute monitor failed: ${t.message}")
        }
    }, "msgr-owner-mute").apply { isDaemon = true }

    init {
        check(Process.myUid() == 2000) { "shell audio owner required" }
        worker.start()
    }

    fun isMuted(): Boolean = serviceClass.getMethod("isStreamMute", Int::class.javaPrimitiveType)
        .invoke(service, AudioManager.STREAM_VOICE_CALL) as Boolean

    fun volume(): Int = serviceClass.getMethod("getStreamVolume", Int::class.javaPrimitiveType)
        .invoke(service, AudioManager.STREAM_VOICE_CALL) as Int

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
            restore()
        }
        worker.join(1000)
    }
}
