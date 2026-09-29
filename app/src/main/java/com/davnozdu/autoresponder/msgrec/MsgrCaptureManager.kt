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
import com.davnozdu.autoresponder.store.HistoryDb
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
    private val stopMonitor = Object()

    @Volatile private var enabled = false
    @Volatile private var stopFlag = false
    @Volatile private var recordingPkg: String? = null
    @Volatile private var recordingPeer: String? = null
    private var worker: Thread? = null

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
        runCatching { evaluate(manager.activePlaybackConfigurations) }
    }

    @Synchronized
    private fun disable() {
        if (!enabled && worker == null) return
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
        if (hitPkg != null && worker == null) startCurrent(ctx, hitPkg)
        else if (hitPkg == null && worker != null) stopCurrent()
    }

    fun onCallNotification(sbn: StatusBarNotification) {
        if (sbn.packageName != recordingPkg) return
        NotifListenerService.callPeer(sbn)?.let { recordingPeer = it }
    }

    private fun startCurrent(ctx: Context, pkg: String) {
        recordingPkg = pkg
        recordingPeer = NotifListenerService.activeCallPeer(pkg)
        stopFlag = false
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        val startedAt = System.currentTimeMillis()
        worker = Thread({ captureSession(ctx, label, startedAt) }, "msgr-ipc").apply { start() }
        LogFile.append("$TAG: старт — $label, shell-хост")
    }

    private fun stopCurrent() {
        synchronized(stopMonitor) {
            stopFlag = true
            stopMonitor.notifyAll()
        }
        worker?.let { runCatching { it.join(15_000) } }
        worker = null
        recordingPkg = null
    }

    private fun captureSession(ctx: Context, label: String, startedAt: Long) {
        runCatching {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 20_000
                val out = DataOutputStream(socket.outputStream)
                val input = DataInputStream(socket.inputStream)
                out.writeUTF("START")
                out.writeUTF(label)
                out.writeLong(startedAt)
                out.flush()
                check(input.readUTF() == "READY") { "shell-хост не принял запись" }
                synchronized(stopMonitor) {
                    while (!stopFlag) stopMonitor.wait()
                }
                out.writeUTF("STOP")
                out.writeUTF(recordingPeer.orEmpty())
                out.flush()
                check(input.readUTF() == "DONE") { "shell-хост не завершил запись" }
                val path = input.readUTF()
                val durationMs = input.readLong()
                val nearNonzero = input.readLong()
                val farNonzero = input.readLong()
                if (path.isNotEmpty() && durationMs > 500) {
                    HistoryDb.get(ctx).amRecInsert(
                        number = null,
                        name = "$label · ${recordingPeer ?: "Неизвестный абонент"}",
                        ts = startedAt, durationMs = durationMs, file = path,
                        reason = "messenger", heard = false,
                    )
                    LogFile.append("$TAG: сохранено $path (${durationMs / 1000}s, mic=$nearNonzero, far=$farNonzero) → журнал")
                }
            }
        }.onFailure { LogFile.append("$TAG: shell-хост: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
