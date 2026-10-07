package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.data.LogFile
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.notif.NotifListenerService
import java.io.DataInputStream
import java.io.DataOutputStream

/** Event-driven call detection in the app; actual audio stays in the module's shell-UID host. */
object MsgrCaptureManager {
    private const val TAG = "msgrec"
    private const val SOCKET = "autoresp_msgr_capture"

    private var appCtx: Context? = null
    private var am: AudioManager? = null
    private var cbThread: HandlerThread? = null
    private var cbHandler: Handler? = null
    private var callback: AudioManager.AudioPlaybackCallback? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var pendingStop: Runnable? = null

    @Volatile private var enabled = false
    @Volatile private var activeJob: CaptureJob? = null
    @Volatile private var answeringPkg: String? = null

    // Messenger call setup briefly bounces MODE_IN_COMMUNICATION (ring -> connect); observed ~1.5 s
    // for Telegram. Tearing the session down and restarting it inside that gap spawns a second
    // submix sink that steals the far-party route, so the far side goes silent to the user. Wait
    // this long for the mode to stay out of a call before actually stopping.
    private const val STOP_DEBOUNCE_MS = 2500L

    private class CaptureJob(val pkg: String, val label: String, @Volatile var peer: String?) {
        val monitor = Object()
        @Volatile var stopped = false
        @Volatile var answering = false
        lateinit var worker: Thread
    }

    @Synchronized
    fun refresh(context: Context) {
        if (Settings(context).msgrRecEnabled || answeringPkg != null) enable(context) else disable()
    }

    @Synchronized fun beginAnswering(context: Context, pkg: String) {
        answeringPkg = pkg
        enable(context)
        evaluate()
        activeJob?.takeIf { it.pkg == pkg }?.answering = true
    }

    @Synchronized fun endAnswering(context: Context, pkg: String, handoff: Boolean) {
        if (answeringPkg != pkg) return
        if (handoff) activeJob?.takeIf { it.pkg == pkg }?.answering = false
        answeringPkg = null
        refresh(context)
    }

    @Synchronized
    private fun enable(context: Context) {
        appCtx = context.applicationContext
        if (enabled) return
        val manager = appCtx!!.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am = manager
        val t = HandlerThread("msgr-cb").apply { start() }
        cbThread = t
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                // The configs are anonymised for a /data app; we only use this as a "something in the
                // audio world changed" trigger and read the actual call state in evaluate().
                runCatching { evaluate() }.onFailure { LogFile.append("$TAG: evaluate: ${it.message}") }
            }
        }
        callback = cb
        val handler = Handler(t.looper)
        cbHandler = handler
        manager.registerAudioPlaybackCallback(cb, handler)
        // The call itself is MODE_IN_COMMUNICATION. Players alone are unreliable: WhatsApp keeps a
        // USAGE_VOICE_COMMUNICATION SoundPool alive ~15 s after hang-up (confirmed live), which used
        // to keep the microphone recording after the call had already ended. A mode change is the
        // authoritative call boundary; it is a public event API, so no polling.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ml = AudioManager.OnModeChangedListener {
                runCatching { evaluate() }
                    .onFailure { LogFile.append("$TAG: evaluate(mode): ${it.message}") }
            }
            modeListener = ml
            manager.addOnModeChangedListener({ handler.post(it) }, ml)
        }
        enabled = true
        LogFile.append("$TAG: детект звонка включён (захват — shell-процесс модуля)")
        val ctx = appCtx!!
        Thread({
            runCatching { MsgrRecordingRecovery.run(ctx) }
            // After an app update the module's shell host still runs the OLD apk. A version
            // handshake now makes a stale host exit so the module restarts it before the next call.
            runCatching { pingHost(ctx) }
        }, "msgr-recover").start()
        runCatching { evaluate() }
    }

    @Synchronized
    private fun disable() {
        if (!enabled && activeJob == null) return
        cancelStop()
        stopCurrent()
        callback?.let { cb -> runCatching { am?.unregisterAudioPlaybackCallback(cb) } }
        callback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            modeListener?.let { ml -> runCatching { am?.removeOnModeChangedListener(ml) } }
        modeListener = null
        cbHandler = null
        cbThread?.quitSafely(); cbThread = null
        enabled = false
        LogFile.append("$TAG: выключено")
    }

    @Synchronized
    private fun evaluate() {
        val ctx = appCtx ?: return
        // A messenger call is exactly the window in which the device is in MODE_IN_COMMUNICATION.
        // Outside it there is no call, but stop only after a debounce so the setup-time bounce does
        // not tear the session down and restart it (which breaks the far-party route, see above).
        if (am?.mode != AudioManager.MODE_IN_COMMUNICATION) {
            scheduleStop()
            return
        }
        // Back in a call: cancel any pending stop from a transient drop. Once a session is armed we
        // leave it running for the whole call, so a mid-call track drop cannot spawn a second one.
        cancelStop()
        if (activeJob != null) return
        // Which messenger is calling: the app that has posted an ongoing CALL notification. The
        // notification's package is authoritative (it is WHICH app posted it), needs no privileged
        // permission, and a SIM call posts none from a whitelisted messenger, so it is excluded for
        // free. The mode may enter IN_COMMUNICATION a beat before the notification appears — the
        // playback callback, the mode change and each notification post all re-trigger evaluate(),
        // so the start fires as soon as the notification is up.
        val whitelist = answeringPkg?.let { setOf(it) } ?: Settings(ctx).msgrRecApps
        val pkg = NotifListenerService.activeCallApp(whitelist) ?: return
        startCurrent(ctx, pkg)
    }

    /** A notification changed: update the peer name and (re)check whether a call has begun. */
    fun onNotificationEvent(sbn: StatusBarNotification) {
        val h = cbHandler ?: return
        h.post {
            activeJob?.let { job ->
                if (sbn.packageName == job.pkg) {
                    val peer = NotifListenerService.callPeer(sbn)
                    if (peer != null && job.peer != peer) {
                        job.peer = peer
                        LogFile.append("$TAG: имя собеседника из уведомления: $peer")
                    }
                }
            }
            runCatching { evaluate() }.onFailure { LogFile.append("$TAG: evaluate(notif): ${it.message}") }
        }
    }

    private fun startCurrent(ctx: Context, pkg: String) {
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        val job = CaptureJob(pkg, label, NotifListenerService.activeCallPeer(pkg))
        job.answering = answeringPkg == pkg
        val startedAt = System.currentTimeMillis()
        activeJob = job
        job.worker = Thread({ captureSession(ctx, job, startedAt) }, "msgr-ipc").apply { start() }
        LogFile.append("$TAG: старт — $label, shell-хост")
    }

    /** Schedule the real stop after a debounce; a call that resumes within the window cancels it. */
    private fun scheduleStop() {
        if (activeJob == null || pendingStop != null) return
        val h = cbHandler ?: run { stopCurrent(); return }
        val r = Runnable {
            synchronized(this) {
                pendingStop = null
                if (am?.mode != AudioManager.MODE_IN_COMMUNICATION) stopCurrent()
            }
        }
        pendingStop = r
        h.postDelayed(r, STOP_DEBOUNCE_MS)
    }

    private fun cancelStop() {
        pendingStop?.let { cbHandler?.removeCallbacks(it); pendingStop = null }
    }

    private fun stopCurrent() {
        val job = activeJob ?: return
        synchronized(job.monitor) {
            job.stopped = true
            job.monitor.notifyAll()
        }
        runCatching { job.worker.join(15_000) }
        activeJob = null
    }

    private fun captureSession(ctx: Context, job: CaptureJob, startedAt: Long) {
        var segmentAt = startedAt
        while (true) {
            val result = runCatching {
                LocalSocket().use { socket ->
                    socket.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 20_000
                    val out = DataOutputStream(socket.outputStream)
                    val input = DataInputStream(socket.inputStream)
                    out.writeUTF("START")
                    out.writeUTF(job.label)
                    out.writeLong(segmentAt)
                    out.flush()
                    check(input.readUTF() == "READY") { "shell-хост не принял запись" }
                    synchronized(job.monitor) {
                        while (!job.stopped) job.monitor.wait()
                    }
                    // Last chance to name the caller if the call notification posted late or was
                    // missed at start (we now start on the mode change, before it appears).
                    if (job.peer.isNullOrBlank())
                        runCatching { NotifListenerService.activeCallPeer(job.pkg) }.getOrNull()?.let { job.peer = it }
                    out.writeUTF("STOP")
                    out.writeUTF(job.peer.orEmpty())
                    out.flush()
                    check(input.readUTF() == "DONE") { "shell-хост не завершил запись" }
                    val path = input.readUTF()
                    val durationMs = input.readLong()
                    val nearNonzero = input.readLong()
                    val farNonzero = input.readLong()
                    if (path.isNotEmpty() && durationMs > 500) {
                        MsgrRecordingRecovery.insertIfMissing(ctx,
                            "${job.label} · ${job.peer ?: "Неизвестный абонент"}",
                            segmentAt, durationMs, path)
                        if (job.answering) com.davnozdu.autoresponder.notif.AutoNotifications.showAmRec(
                            ctx, job.peer, null, durationMs)
                        LogFile.append("$TAG: сохранено $path (${durationMs / 1000}s, mic=$nearNonzero, far=$farNonzero) → журнал")
                    } else {
                        // Nothing worth keeping (call never really started); drop the empty file.
                        runCatching { if (path.isNotEmpty()) java.io.File(path).delete() }
                    }
                }
            }
            if (result.isSuccess) return
            val error = result.exceptionOrNull()
            LogFile.append("$TAG: shell-хост: ${error?.javaClass?.simpleName}: ${error?.message}")
            // The host may have finished the WAV just as the socket broke. The recordings
            // screen performs the same recovery when opened, so a late file is not lost.
            runCatching { MsgrRecordingRecovery.run(ctx) }
            synchronized(job.monitor) {
                if (job.stopped) return
                job.monitor.wait(1000)
                if (job.stopped) return
            }
            segmentAt = System.currentTimeMillis()
        }
    }

    /**
     * Tells the shell host which APK the app now runs. A host left over from a previous APK version
     * exits on mismatch (the module then restarts it fresh), so the IPC protocol never gets split
     * across a stale host and a fresh app. Best-effort: if the host is absent the next call's START
     * simply fails and retries, as before.
     */
    private fun pingHost(ctx: Context) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 5_000
            val out = DataOutputStream(socket.outputStream)
            val input = DataInputStream(socket.inputStream)
            out.writeUTF("HELLO")
            out.writeUTF(ctx.applicationInfo.sourceDir)
            out.flush()
            val reply = runCatching { input.readUTF() }.getOrNull()
            if (reply == "RESTART")
                LogFile.append("$TAG: shell-хост на старом APK — перезапускается модулем")
        }
    }
}
