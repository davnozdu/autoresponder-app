package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.call.Greeting
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.notif.NotifListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Opt-in experimental answering for Telegram, WhatsApp and WhatsApp Business. */
object MsgrAnswerManager {
    private val supported = IncomingCallNotification.packages
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attempted = LinkedHashSet<String>()
    @Volatile private var busy = false
    @Volatile private var activeSocket: LocalSocket? = null

    fun cancel() { runCatching { activeSocket?.close() } }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun dispatchAnswer(app: Context, current: StatusBarNotification, uid: Int) {
        val args = listOf(current.packageName, current.key, uid.toString(), current.notification.`when`.toString())
        val command = "CLASSPATH=" + shellQuote(app.applicationInfo.sourceDir) +
            " app_process /system/bin com.davnozdu.autoresponder.msgrec.MsgrAnswerDispatch " +
            args.joinToString(" ") { shellQuote(it) }
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            check(process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { "превышено время команды ответа" }
            val result = process.inputStream.bufferedReader().readText().take(1500)
            check(process.exitValue() == 0 && result.contains("ANSWER_DISPATCH SENT package=${current.packageName}")) {
                "диспетчер ответа: $result"
            }
        } finally { process.destroy() }
    }

    @Synchronized
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        val s = Settings(context)
        if (!s.msgrAmEnabled || sbn.packageName !in supported || sbn.packageName !in s.msgrRecApps) return
        if (System.currentTimeMillis() - sbn.postTime !in 0..30_000) return
        val action = IncomingCallNotification.answer(sbn.notification) ?: return
        if (action.creatorPackage != sbn.packageName) return
        val token = "${sbn.key}:${sbn.notification.`when`}"
        if (busy || !attempted.add(token)) return
        while (attempted.size > 64) attempted.remove(attempted.first())
        busy = true
        val app = context.applicationContext
        scope.launch {
            val log = EventLog(app)
            try {
                val settings = Settings(app)
                val path = Greeting.prepare(app, settings.amGreetingLang.takeIf { it.isNotBlank() })
                    ?: error("не удалось подготовить приветствие")
                val file = File(path)
                check(file.length() in 4..(VoipAudioInjector.MAX_BYTES.toLong() * 2)) { "приветствие длиннее 120 секунд" }
                val pcm = GreetingPcm.mono(file.readBytes())
                @Suppress("DEPRECATION")
                val uid = app.packageManager.getApplicationInfo(sbn.packageName, 0).uid
                LocalSocket().use { socket ->
                    activeSocket = socket
                    socket.connect(LocalSocketAddress(MsgrInjectionHost.SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 135_000
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    output.writeUTF("ARM"); output.writeInt(uid)
                    output.writeInt(pcm.size); output.write(pcm); output.flush()
                    check(input.readUTF() == "READY") { "хост не подготовил подачу звука" }
                    delay(2000) // Allow the owner to answer first; then revalidate the exact notification.
                    val current = NotifListenerService.current(sbn.key)
                    val currentAction = current?.let { IncomingCallNotification.answer(it.notification) }
                    check(Settings(app).msgrAmEnabled && currentAction != null &&
                        currentAction.creatorPackage == sbn.packageName) {
                        "входящий звонок уже завершён или принят вручную"
                    }
                    dispatchAnswer(app, current!!, uid)
                    log.add("MSGR AM: команда ответа отправлена ${sbn.packageName}; " +
                        "действие=${if (currentAction.isActivity) "activity" else "service/broadcast"}; отправитель=root; маршрут приветствия готов")
                    val am = app.getSystemService(AudioManager::class.java)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
                    while (am.mode != AudioManager.MODE_IN_COMMUNICATION ||
                        NotifListenerService.activeCallApp(setOf(sbn.packageName)) == null) {
                        check(Settings(app).msgrAmEnabled) { "автоответ мессенджеров выключен" }
                        check(android.os.SystemClock.elapsedRealtime() < deadline) { "звонок не подключился" }
                        delay(100)
                    }
                    delay(2000) // Audio setup can enter communication mode before the peer is connected.
                    check(Settings(app).msgrAmEnabled) { "автоответ мессенджеров выключен" }
                    check(am.mode == AudioManager.MODE_IN_COMMUNICATION) { "звонок завершён до приветствия" }
                    log.add("MSGR AM: разговор подключён ${sbn.packageName}; отправляем приветствие")
                    output.writeUTF("PLAY"); output.flush()
                    val callEnd = scope.launch {
                        var awaySince = 0L
                        while (activeSocket === socket) {
                            delay(200)
                            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) awaySince = 0L
                            else {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (awaySince == 0L) awaySince = now
                                if (now - awaySince >= 2500) { socket.close(); break }
                            }
                        }
                    }
                    try {
                        check(input.readUTF() == "DONE") { "ошибка подачи приветствия" }
                    } finally { callEnd.cancel() }
                    log.add("MSGR AM: запись приветствия в виртуальный поток завершена ${sbn.packageName}; микрофон восстановлен")
                }
            } catch (t: Exception) {
                log.add("MSGR AM: ${sbn.packageName}: ${t.javaClass.simpleName}: ${t.message}")
            } finally { activeSocket = null; busy = false }
        }
    }
}
