package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
    private var callback: AudioManager.AudioPlaybackCallback? = null

    @Volatile private var enabled = false
    @Volatile private var activeJob: CaptureJob? = null

    private class CaptureJob(val pkg: String, val label: String, @Volatile var peer: String?) {
        val monitor = Object()
        @Volatile var stopped = false
        lateinit var worker: Thread
    }

    @Synchronized
    fun refresh(context: Context) {
        if (Settings(context).msgrRecEnabled) enable(context) else disable()
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
                runCatching { evaluate(configs) }.onFailure { LogFile.append("$TAG: evaluate: ${it.message}") }
            }
        }
        callback = cb
        manager.registerAudioPlaybackCallback(cb, Handler(t.looper))
        enabled = true
        LogFile.append("$TAG: детект звонка включён (захват — shell-процесс модуля)")
        Thread({ runCatching { MsgrRecordingRecovery.run(appCtx!!) } }, "msgr-recover").start()
        runCatching { evaluate(manager.activePlaybackConfigurations) }
    }

    @Synchronized
    private fun disable() {
        if (!enabled && activeJob == null) return
        stopCurrent()
        callback?.let { cb -> runCatching { am?.unregisterAudioPlaybackCallback(cb) } }
        callback = null
        cbThread?.quitSafely(); cbThread = null
        enabled = false
        LogFile.append("$TAG: выключено")
    }

    @Synchronized
    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val ctx = appCtx ?: return
        val whitelist = Settings(ctx).msgrRecApps
        var hitPkg: String? = null
        for (c in configs) {
            if (c.audioAttributes?.usage != AudioAttributes.USAGE_VOICE_COMMUNICATION) continue
            val uid = runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getClientUid").invoke(c) as Int
            }.getOrDefault(-1)
            if (uid < 10000) continue
            hitPkg = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull { it in whitelist }
            if (hitPkg != null) break
        }
        if (hitPkg != null && activeJob == null) startCurrent(ctx, hitPkg)
        else if (hitPkg == null && activeJob != null) stopCurrent()
    }

    fun onCallNotification(sbn: StatusBarNotification) {
        val job = activeJob ?: return
        if (sbn.packageName != job.pkg) return
        NotifListenerService.callPeer(sbn)?.let { job.peer = it }
    }

    private fun startCurrent(ctx: Context, pkg: String) {
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        val job = CaptureJob(pkg, label, NotifListenerService.activeCallPeer(pkg))
        val startedAt = System.currentTimeMillis()
        activeJob = job
        job.worker = Thread({ captureSession(ctx, job, startedAt) }, "msgr-ipc").apply { start() }
        LogFile.append("$TAG: старт — $label, shell-хост")
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
                        LogFile.append("$TAG: сохранено $path (${durationMs / 1000}s, mic=$nearNonzero, far=$farNonzero) → журнал")
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
}
