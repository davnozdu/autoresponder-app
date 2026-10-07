package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.call.AnswerMachineVibration
import com.davnozdu.autoresponder.call.Greeting
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.notif.CallerOverlay
import com.davnozdu.autoresponder.notif.NotifListenerService
import com.davnozdu.autoresponder.rules.*
import com.davnozdu.autoresponder.store.HistoryDb
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Existing voice-call settings, with messenger-scoped audio and original call actions. */
object MsgrAnswerManager {
    private val supported = IncomingCallNotification.packages
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attempted = LinkedHashSet<String>()
    @Volatile private var busy = false
    @Volatile private var activeSocket: LocalSocket? = null
    @Volatile private var connected = false
    @Volatile private var busyLogged: String? = null
    private data class Plan(val route: MsgrCallPolicy.Route, val peer: String?, val lang: String?, val text: String?)
    private enum class Decision { ACCEPT, DECLINE, VOICEMAIL }
    private class State {
        val decision = AtomicReference<Decision?>(null)
        val played = AtomicInteger(-1)
        @Volatile var ended = false
        @Volatile var error: String? = null
        @Volatile var ownerMuted = false
    }

    fun cancel() { hangUp(activeSocket) }
    /**
     * close() alone is not enough: a reader blocked in readUTF() on another thread is NOT woken
     * by close() on Linux, and the kernel keeps the socket alive (no EOF for the host) until that
     * read returns. The session then never finished, `busy` stayed true and the NEXT incoming
     * call was silently dropped — every other call on 2026-10-07. shutdown() wakes both sides.
     */
    private fun hangUp(socket: LocalSocket?) {
        socket ?: return
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
    /** Protected ADB check of the ACTUAL configured screening greeting, without taking a call. */
    internal fun probeGreeting(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { greeting(app, Plan(MsgrCallPolicy.Route.SCREENING, null, null, null)) }
                .onSuccess { EventLog(app).add("MSGR GREETING PROBE: PASS screening bytes=${it.size} " +
                    "durationMs=${it.size * 1000L / (VoipAudioInjector.RATE * 2)}") }
                .onFailure { EventLog(app).add("MSGR GREETING PROBE: FAIL ${it.message}") }
        }
    }
    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun dispatch(app: Context, current: StatusBarNotification, uid: Int, end: Boolean = false) {
        val args = (if (end) listOf("--end") else emptyList()) + listOf(current.packageName,
            current.key, uid.toString(), current.notification.`when`.toString())
        val command = "CLASSPATH=" + shellQuote(app.applicationInfo.sourceDir) +
            " app_process /system/bin com.davnozdu.autoresponder.msgrec.MsgrAnswerDispatch " +
            args.joinToString(" ") { shellQuote(it) }
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            check(process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { "превышено время команды звонка" }
            val result = process.inputStream.bufferedReader().readText().take(1500)
            check(process.exitValue() == 0 && result.contains(
                "ANSWER_DISPATCH ${if (end) "ENDED" else "SENT"} package=${current.packageName}")) {
                "диспетчер звонка: $result"
            }
        } finally { process.destroy() }
    }

    private fun plan(app: Context, sbn: StatusBarNotification): Plan {
        val s = Settings(app)
        val peer = NotifListenerService.callPeer(sbn)
        val number = peer?.takeIf { PhoneMask.looksLikeNumber(it) }
        val bl = HistoryDb.get(app).blacklistMatch(number, peer)
        val skip = if (number != null) SkipPolicy.reason(app, number, s, isCall = true)
            else SkipPolicy.reasonForSender(app, peer, s, isCall = true)
        val am = app.getSystemService(AudioManager::class.java)
        val route = MsgrCallPolicy.route(
            enabled = s.enabled && s.respondCalls && s.msgrAmEnabled &&
                sbn.packageName in supported && sbn.packageName in s.msgrRecApps,
            paused = AutoReplyState.isPaused(app),
            alreadyInCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION,
            blacklistAllowsCalls = bl?.onCalls, skip = skip != null, vacation = s.vacationModeEnabled,
            dnd = ClosedState.isDndOn(app),
            screening = ScreeningPolicy.shouldScreen(s.screeningEnabled,
                s.autoScreeningByDnd || ScreeningPolicy.isInWindow(app, s), skip != null) &&
                android.provider.Settings.canDrawOverlays(app),
            headset = HeadsetPolicy.shouldForceAnswer(s.headsetForceAnswer,
                AudioRouteUtil.isBluetoothHeadsetActive(app), skip != null),
            closed = ClosedState.isClosed(app, s), closedVoice = s.callClosedMode == 1)
        if (route == MsgrCallPolicy.Route.NONE)
            EventLog(app).add("MSGR AM: ${sbn.packageName} — ${skip ?: "автоответ по текущим правилам не нужен"}, обычный звонок")
        return Plan(route, peer, if (route == MsgrCallPolicy.Route.BLACKLIST)
            bl?.callPromptLang?.ifBlank { null } else s.amGreetingLang.ifBlank { null },
            if (route == MsgrCallPolicy.Route.BLACKLIST) bl?.callPrompt else null)
    }

    private suspend fun greeting(app: Context, plan: Plan): ByteArray {
        val s = Settings(app)
        val path = withTimeoutOrNull(15_000L) {
            when (plan.route) {
                MsgrCallPolicy.Route.SCREENING -> Greeting.prepareScreening(app,
                    s.screeningDefaultLang, s.screeningWaitSec.coerceIn(5, 300) * 1000)
                MsgrCallPolicy.Route.VACATION -> Greeting.prepareVacation(app, s.vacationDefaultLang)
                else -> Greeting.prepare(app, plan.lang, plan.text)
            }
        } ?: error("не удалось подготовить приветствие")
        return pcm(path)
    }
    private fun pcm(path: String): ByteArray {
        val file = File(path)
        check(file.length() in 4..(VoipAudioInjector.MAX_SESSION_CLIP_BYTES.toLong() * 2)) {
            "приветствие/ожидание длиннее допустимого"
        }
        return GreetingPcm.mono(file.readBytes(), VoipAudioInjector.MAX_SESSION_CLIP_BYTES)
    }

    @Synchronized fun onPosted(context: Context, sbn: StatusBarNotification) {
        val receivedAt = SystemClock.elapsedRealtime()
        val s = Settings(context)
        if (!s.msgrAmEnabled || sbn.packageName !in supported || sbn.packageName !in s.msgrRecApps) return
        if (System.currentTimeMillis() - sbn.postTime !in 0..30_000) return
        val action = IncomingCallNotification.answer(sbn.notification) ?: return
        if (action.creatorPackage != sbn.packageName) return
        val token = "${sbn.key}:${sbn.notification.`when`}"
        if (busy) {
            // A new incoming call must never inherit an old voicemail timeout/hangup.
            // Before the line connects, WhatsApp re-posts the SAME call with a new `when` —
            // not a second call, so only an overlap with a connected session is logged.
            if (connected && token !in attempted && token != busyLogged) {
                busyLogged = token
                EventLog(context).add("MSGR AM: ${sbn.packageName} — новый звонок, пока занят прежний сеанс; пропуск")
            }
            if (connected && token !in attempted) cancel()
            return
        }
        if (!attempted.add(token)) return
        while (attempted.size > 64) attempted.remove(attempted.first())
        busy = true
        val app = context.applicationContext
        scope.launch {
            try {
                val selected = plan(app, sbn)
                if (selected.route == MsgrCallPolicy.Route.NONE) return@launch
                runSession(app, sbn, selected, greeting(app, selected), receivedAt)
            } catch (t: Exception) {
                EventLog(app).add("MSGR AM: ${sbn.packageName}: ${t.javaClass.simpleName}: ${t.message}")
            } finally { activeSocket = null; connected = false; busy = false }
        }
    }

    private suspend fun runSession(app: Context, sbn: StatusBarNotification, selected: Plan,
                                   audio: ByteArray, receivedAt: Long) = coroutineScope {
        val pkg = sbn.packageName
        val log = EventLog(app)
        @Suppress("DEPRECATION")
        val uid = app.packageManager.getApplicationInfo(pkg, 0).uid
        val am = app.getSystemService(AudioManager::class.java)
        val s = Settings(app)
        val state = State()
        val vibration = AnswerMachineVibration(app)
        var reader: Job? = null
        var monitor: Job? = null
        var screeningCard = false
        var handedOff = false
        val endToken = AtomicReference<StatusBarNotification?>(null)
        val volumeLock = Any()
        LocalSocket().use { socket ->
            activeSocket = socket
            try {
                socket.connect(LocalSocketAddress(MsgrInjectionHost.SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 900_000
                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)
                output.writeUTF("ARM_SESSION"); output.writeInt(uid)
                output.writeInt(audio.size); output.write(audio); output.flush()
                check(input.readUTF() == "READY") { "хост не подготовил подачу звука" }
                // Preparation/ARM count toward the two seconds, rather than adding another delay.
                delay(MsgrCallPolicy.remainingAnswerDelay(receivedAt, SystemClock.elapsedRealtime()))
                val current = NotifListenerService.current(sbn.key) ?: error("входящий звонок завершён")
                check(IncomingCallNotification.answer(current.notification)?.creatorPackage == pkg &&
                    plan(app, current).route == selected.route) { "звонок принят или условия автоответа изменились" }
                dispatch(app, current, uid)
                log.add("MSGR AM: команда ответа отправлена $pkg; режим=${selected.route}; отправитель=root")
                val screening = selected.route == MsgrCallPolicy.Route.SCREENING
                if (screening) {
                    // The card goes up together with the answer command, not after the connection
                    // wait + 2 s settle: the owner used to look at an empty screen for ~2–3 s.
                    // A decision taken before the line connects is simply applied once it does.
                    screeningCard = true
                    CallerOverlay.showScreening(app, selected.peer ?: pkg, null,
                        onAccept = { state.decision.compareAndSet(null, Decision.ACCEPT) },
                        onDecline = { state.decision.compareAndSet(null, Decision.DECLINE) },
                        onTransfer = { state.decision.compareAndSet(null, Decision.VOICEMAIL) })
                    if (s.amVibrateToOwner) vibration.start(s.screeningWaitSec.coerceIn(5, 300) + 20,
                        s.amVibrationIntervalSec, this)
                }
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (am.mode != AudioManager.MODE_IN_COMMUNICATION ||
                    NotifListenerService.activeCallApp(setOf(pkg)) == null) {
                    check(Settings(app).msgrAmEnabled && SystemClock.elapsedRealtime() < deadline) { "звонок не подключился" }
                    delay(100)
                }
                delay(2000)
                check(am.mode == AudioManager.MODE_IN_COMMUNICATION && Settings(app).msgrAmEnabled) {
                    "звонок завершён до приветствия"
                }
                MsgrCaptureManager.beginAnswering(app, pkg)
                connected = true
                com.davnozdu.autoresponder.notif.DndStats.onIncoming(app, isCall = true)
                fun findEnd() = NotifListenerService.callNotifications(pkg).firstOrNull {
                    IncomingCallNotification.end(it.notification)?.creatorPackage == pkg
                }
                endToken.set(findEnd())
                fun muteOwner(mute: Boolean) = synchronized(volumeLock) {
                    if (mute) {
                        state.ownerMuted = true
                        runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
                    } else if (state.ownerMuted) {
                        runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
                        state.ownerMuted = false
                    }
                }
                // Not a child of this session: even if a read ever got stuck again, it must not keep
                // the session (and with it `busy`) alive and swallow the next incoming call.
                reader = scope.launch {
                    try {
                        while (true) {
                            val reply = input.readUTF()
                            when {
                                reply.startsWith("PLAYED:") -> state.played.set(reply.substringAfter(':').toInt())
                                reply == "DONE" -> break
                                else -> { state.error = reply; break }
                            }
                        }
                    } catch (t: Exception) {
                        if (!state.ended) state.error = t.message ?: "сокет закрыт"
                    }
                }
                monitor = launch {
                    var awaySince = 0L
                    while (isActive) {
                        delay(200)
                        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) awaySince = 0L
                        else {
                            val now = SystemClock.elapsedRealtime()
                            if (awaySince == 0L) awaySince = now
                            if (now - awaySince >= 2500) { state.ended = true; hangUp(socket); break }
                        }
                        if (!Settings(app).msgrAmEnabled) { state.ended = true; hangUp(socket); break }
                        if (endToken.get() == null) endToken.compareAndSet(null, findEnd())
                        synchronized(volumeLock) {
                            if (state.ownerMuted) runCatching {
                                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
                            }
                        }
                    }
                }
                suspend fun waitFor(ms: Long, done: () -> Boolean = { false }) {
                    val until = SystemClock.elapsedRealtime() + ms
                    while (!state.ended && SystemClock.elapsedRealtime() < until && !done()) {
                        state.error?.let { error(it) }
                        delay(100)
                    }
                }
                fun endCall() {
                    if (state.ended || am.mode != AudioManager.MODE_IN_COMMUNICATION) return
                    val original = endToken.get() ?: error("у мессенджера нет действия завершения звонка")
                    val latest = NotifListenerService.current(original.key)
                    check(latest != null && latest.uid == uid &&
                        latest.notification.`when` == original.notification.`when` &&
                        IncomingCallNotification.end(latest.notification) ==
                        IncomingCallNotification.end(original.notification)) { "активный звонок изменился" }
                    dispatch(app, latest, uid, end = true)
                    log.add("MSGR AM: команда завершения $pkg отправлена")
                }
                suspend fun voicemail(clipId: Int, seconds: Int) {
                    if (screeningCard) { CallerOverlay.hide(app); screeningCard = false }
                    vibration.stop()
                    muteOwner(s.amSilentToOwner)
                    waitFor(425_000) { state.played.get() == clipId }
                    if (state.ended) return
                    check(state.played.get() == clipId) { "таймаут приветствия" }
                    log.add("MSGR AM: $pkg — запись сообщения, максимум ${seconds}s")
                    waitFor(seconds * 1000L)
                    if (!state.ended) {
                        endCall()
                        waitFor(6000) { am.mode != AudioManager.MODE_IN_COMMUNICATION }
                    }
                }
                muteOwner(screening || s.amSilentToOwner)
                log.add("MSGR AM: разговор подключён $pkg; приветствие ${selected.route}")
                output.writeUTF("PLAY"); output.flush()
                if (screening) {
                    waitFor(s.screeningWaitSec.coerceIn(5, 300) * 1000L) { state.decision.get() != null }
                    if (!state.ended) {
                        state.decision.compareAndSet(null, Decision.VOICEMAIL)
                        when (state.decision.get()) {
                            Decision.ACCEPT -> {
                                handedOff = true
                                log.add("MSGR AM: $pkg — владелец принял разговор; микрофон восстанавливается")
                            }
                            Decision.DECLINE -> {
                                endCall()
                                waitFor(6000) { am.mode != AudioManager.MODE_IN_COMMUNICATION }
                            }
                            Decision.VOICEMAIL -> {
                                CallerOverlay.hide(app); screeningCard = false
                                vibration.stop()
                                // Silence while TTS prepares, without recreating the microphone route.
                                output.writeUTF("REPLACE"); output.writeInt(1); output.writeInt(2)
                                output.write(byteArrayOf(0, 0)); output.flush()
                                // Same greeting as the DND answering machine (owner's file or TTS).
                                val vm = withTimeoutOrNull(15_000) {
                                    Greeting.prepare(app, s.amGreetingLang.ifBlank { null })
                                } ?: error("не удалось подготовить приветствие голосовой почты")
                                val next = pcm(vm)
                                if (!state.ended) {
                                    output.writeUTF("REPLACE"); output.writeInt(2); output.writeInt(next.size)
                                    output.write(next); output.flush()
                                    log.add("MSGR AM: $pkg — переход на автоответчик")
                                    voicemail(2, s.voicemailMaxSec.coerceIn(5, 300))
                                }
                            }
                            null -> Unit
                        }
                    }
                } else voicemail(0, s.amMaxMessageSec.coerceIn(5, 300))
                if (!state.ended) {
                    output.writeUTF("RELEASE"); output.flush()
                    withTimeoutOrNull(4000) { reader?.join() }
                }
            } finally {
                state.ended = true
                hangUp(socket)
                monitor?.cancel(); reader?.cancel(); vibration.stop()
                if (screeningCard) CallerOverlay.hide(app)
                synchronized(volumeLock) {
                    if (state.ownerMuted) runCatching {
                        am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                    }
                    state.ownerMuted = false
                }
                MsgrCaptureManager.endAnswering(app, pkg, handedOff)
                log.add("MSGR AM: $pkg — сессия завершена, временный аудиомаршрут снят")
            }
        }
    }
}
