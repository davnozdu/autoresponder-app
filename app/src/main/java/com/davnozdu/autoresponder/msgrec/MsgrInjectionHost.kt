package com.davnozdu.autoresponder.msgrec

import android.net.LocalServerSocket
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream

/** Separate socket/thread: a recording session holds the capture socket until hang-up. */
internal object MsgrInjectionHost {
    const val SOCKET = "autoresp_msgr_inject"

    fun start(appUid: Int) {
        Thread({
            Looper.prepare()
            runCatching {
                LocalServerSocket(SOCKET).use { server ->
                    println("msgrec injection host ready")
                    while (true) server.accept().use { socket ->
                        if (socket.peerCredentials.uid != appUid) return@use
                        socket.soTimeout = 45_000
                        val input = DataInputStream(socket.inputStream)
                        val output = DataOutputStream(socket.outputStream)
                        runCatching {
                            check(input.readUTF() == "ARM")
                            val uid = input.readInt()
                            require(uid >= 10_000 && uid != appUid) { "messenger UID required" }
                            val length = input.readInt()
                            require(length in 2..VoipAudioInjector.MAX_BYTES && length % 2 == 0)
                            val pcm = ByteArray(length)
                            input.readFully(pcm)
                            VoipAudioInjector(uid, pcm).use { injector ->
                                VoipInputDiagnostics.snapshot("armed", uid)
                                output.writeUTF("READY"); output.flush()
                                check(input.readUTF() == "PLAY")
                                println("msgrec inject play uid=$uid bytes=$length")
                                VoipInputDiagnostics.snapshot("play", uid)
                                injector.play()
                                // EOF/app death/settings disable must restore the route immediately.
                                val disconnect = Thread({
                                    runCatching { input.readUTF() }
                                    injector.close()
                                }, "msgr-inject-disconnect").apply { isDaemon = true; start() }
                                val diagnose = Thread({
                                    for (i in 1..3) {
                                        try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                                        VoipInputDiagnostics.snapshot("playing-$i", uid)
                                    }
                                }, "msgr-inject-diagnostics").apply { isDaemon = true; start() }
                                try { injector.awaitDone() } finally {
                                    diagnose.interrupt(); diagnose.join(1000)
                                    runCatching { socket.shutdownInput() }
                                    disconnect.join(2000)
                                }
                            }
                            VoipInputDiagnostics.snapshot("restored", uid)
                            // DONE means the policy is removed, not just the last write accepted.
                            output.writeUTF("DONE"); output.flush()
                            println("msgrec inject done uid=$uid; microphone restored")
                        }.onFailure { t ->
                            System.err.println("msgrec inject: ${t.javaClass.simpleName}: ${t.message}")
                            runCatching { output.writeUTF("ERROR:${t.message?.take(200)}"); output.flush() }
                        }
                    }
                }
            }.onFailure { System.err.println("msgrec injection host failed: ${it.message}") }
        }, "msgr-inject-server").apply { isDaemon = true; start() }
    }
}
