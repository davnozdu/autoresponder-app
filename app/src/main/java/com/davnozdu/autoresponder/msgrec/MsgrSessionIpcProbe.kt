package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.davnozdu.autoresponder.data.EventLog
import java.io.DataInputStream
import java.io.DataOutputStream

/** Protected ADB hook: silent clips, no call actions/settings/files; idle device only. */
internal object MsgrSessionIpcProbe {
    fun start(context: Context) {
        val app = context.applicationContext
        Thread({
            val log = EventLog(app)
            runCatching {
                val am = app.getSystemService(AudioManager::class.java)
                check(am.mode == AudioManager.MODE_NORMAL)
                val wasMuted = am.isStreamMute(AudioManager.STREAM_VOICE_CALL)
                val volume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                fun awaitMute(muted: Boolean) {
                    val until = android.os.SystemClock.elapsedRealtime() + 3000
                    while (am.isStreamMute(AudioManager.STREAM_VOICE_CALL) != muted &&
                        android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                    check(am.isStreamMute(AudioManager.STREAM_VOICE_CALL) == muted)
                }
                @Suppress("DEPRECATION")
                val uid = app.packageManager.getApplicationInfo("org.telegram.messenger", 0).uid
                // Graceful release, disconnect before answering, disconnect while greeting plays.
                for (exit in 0..2) {
                LocalSocket().use { socket ->
                    socket.connect(LocalSocketAddress(MsgrInjectionHost.SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 8000
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    output.writeUTF("ARM_SESSION_V2"); output.writeInt(uid); output.writeBoolean(true); output.writeInt(2)
                    output.write(byteArrayOf(0, 0)); output.flush()
                    check(input.readUTF() == "READY")
                    awaitMute(true)
                    check(am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0)
                    if (exit == 1) {
                        socket.shutdownInput(); socket.shutdownOutput()
                        return@use
                    }
                    output.writeUTF("PLAY"); output.flush()
                    check(input.readUTF() == "PLAYED:0")
                    if (exit == 2) {
                        socket.shutdownInput(); socket.shutdownOutput()
                        return@use
                    }
                    output.writeUTF("OWNER_SILENT"); output.writeBoolean(false); output.flush()
                    awaitMute(wasMuted)
                    output.writeUTF("OWNER_SILENT"); output.writeBoolean(true); output.flush()
                    awaitMute(true)
                    output.writeUTF("REPLACE"); output.writeInt(1); output.writeInt(2)
                    output.write(byteArrayOf(0, 0)); output.flush()
                    check(input.readUTF() == "PLAYED:1")
                    output.writeUTF("RELEASE"); output.flush()
                    check(input.readUTF() == "DONE")
                }
                awaitMute(wasMuted)
                check(am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == volume)
                }
                log.add("MSGR SESSION IPC PROBE: PASS volume=0/restore/toggle/EOF-before-play/EOF-playing/repeat")
            }.onFailure { log.add("MSGR SESSION IPC PROBE: FAIL ${it.javaClass.simpleName}: ${it.message}") }
        }, "msgr-session-ipc-probe").start()
    }
}
