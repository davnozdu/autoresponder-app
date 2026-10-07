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
                check(app.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL)
                @Suppress("DEPRECATION")
                val uid = app.packageManager.getApplicationInfo("org.telegram.messenger", 0).uid
                LocalSocket().use { socket ->
                    socket.soTimeout = 8000
                    socket.connect(LocalSocketAddress(MsgrInjectionHost.SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    output.writeUTF("ARM_SESSION"); output.writeInt(uid); output.writeInt(2)
                    output.write(byteArrayOf(0, 0)); output.flush()
                    check(input.readUTF() == "READY")
                    output.writeUTF("PLAY"); output.flush()
                    check(input.readUTF() == "PLAYED:0")
                    output.writeUTF("REPLACE"); output.writeInt(1); output.writeInt(2)
                    output.write(byteArrayOf(0, 0)); output.flush()
                    check(input.readUTF() == "PLAYED:1")
                    output.writeUTF("RELEASE"); output.flush()
                    check(input.readUTF() == "DONE")
                }
                log.add("MSGR SESSION IPC PROBE: PASS READY/PLAYED:0/PLAYED:1/DONE")
            }.onFailure { log.add("MSGR SESSION IPC PROBE: FAIL ${it.javaClass.simpleName}: ${it.message}") }
        }, "msgr-session-ipc-probe").start()
    }
}
