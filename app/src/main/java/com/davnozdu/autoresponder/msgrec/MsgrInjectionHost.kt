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
                            val command = input.readUTF()
                            check(command == "ARM" || command == "ARM_SESSION_V2")
                            val session = command == "ARM_SESSION_V2"
                            val uid = input.readInt()
                            require(uid >= 10_000 && uid != appUid) { "messenger UID required" }
                            val silentOwner = session && input.readBoolean()
                            val length = input.readInt()
                            val maxBytes = if (session) VoipAudioInjector.MAX_SESSION_CLIP_BYTES else VoipAudioInjector.MAX_BYTES
                            require(length in 2..maxBytes && length % 2 == 0)
                            val pcm = ByteArray(length)
                            input.readFully(pcm)
                            fun reply(value: String) = synchronized(output) {
                                output.writeUTF(value); output.flush()
                            }
                            VoipOwnerAudio().use { ownerAudio ->
                                // ARM before answering: no early burst of peer audio in a headset.
                                ownerAudio.setSilent(silentOwner)
                                VoipAudioInjector(uid, pcm, keepOpen = session, onClipDone = { id ->
                                    runCatching { reply(if (id < 0) "ERROR:inject session ended" else "PLAYED:$id") }
                                    if (id < 0) runCatching { socket.shutdownInput() }
                                }).use { injector ->
                                    VoipInputDiagnostics.snapshot("armed", uid)
                                    output.writeUTF("READY"); output.flush()
                                    check(input.readUTF() == "PLAY")
                                    println("msgrec inject play uid=$uid bytes=$length")
                                    VoipInputDiagnostics.snapshot("play", uid)
                                    injector.play()
                                    if (session) {
                                        // This reader owns the socket: EOF cancels, replacement stays
                                        // on the SAME route. Only the audio worker reports clip completion.
                                        socket.soTimeout = 900_000
                                        val diagnose = Thread({
                                            for (i in 1..3) {
                                                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                                                VoipInputDiagnostics.snapshot("playing-$i", uid)
                                            }
                                        }, "msgr-session-diagnostics").apply { isDaemon = true; start() }
                                        try {
                                            var lastId = 0
                                            while (true) {
                                                when (input.readUTF()) {
                                                    "RELEASE" -> break
                                                    "OWNER_SILENT" -> ownerAudio.setSilent(input.readBoolean())
                                                    "REPLACE" -> {
                                                        val id = input.readInt()
                                                        val size = input.readInt()
                                                        require(id > lastId && size in 2..maxBytes && size % 2 == 0)
                                                        val next = ByteArray(size)
                                                        input.readFully(next)
                                                        injector.replace(id, next)
                                                        lastId = id
                                                    }
                                                    else -> error("unknown inject command")
                                                }
                                            }
                                        } finally { diagnose.interrupt(); diagnose.join(1000) }
                                    } else {
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
                                }
                            } // Restore output even on EOF, failed answer, timeout or app death.
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
