/*
 * Messenger (VoIP) call recording — in-process, event-driven capture for AutoResponder.
 *
 * Runs inside the already-persistent NotifListenerService (no extra service, no polling). The app is
 * a privileged system app (installed to /system/priv-app by the KSU module, with a privapp-permissions
 * allowlist for CAPTURE_AUDIO_OUTPUT / CAPTURE_VOICE_COMMUNICATION_OUTPUT / MODIFY_AUDIO_ROUTING), so it
 * holds the capture privilege directly — no shell/app_process host is needed.
 *
 * Battery model: while a call is not in progress there is no work at all — the far-party AudioPolicy
 * is armed (free: no wakelock, no active record track) and we simply wait for the framework's
 * AudioPlaybackCallback to fire when a VoIP playback appears. Capture (and its CPU cost) exists only
 * for the duration of a whitelisted call.
 *
 * Capture primitives ([VoipAudioPolicy], [BypassedAudioRecord]) are derived from CallVault
 * (GPL-3.0 + §7); see LICENSE and NOTICE.md.
 *
 *  Copyright (C) 2026-present davnozdu — GPL-3.0-or-later with §7 additional terms.
 */

package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import com.davnozdu.autoresponder.data.LogFile
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.store.HistoryDb
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MsgrCaptureManager {
    private const val TAG = "msgrec"
    private const val RATE = VoipAudioPolicy.SAMPLE_RATE // 48 kHz
    private const val OUT_DIR = "/sdcard/Music/Recordings/Messenger"
    private const val USAGE_VOICE_COMMUNICATION = AudioAttributes.USAGE_VOICE_COMMUNICATION

    private var appCtx: Context? = null
    private var am: AudioManager? = null
    private var cbThread: HandlerThread? = null
    private var callback: AudioManager.AudioPlaybackCallback? = null

    @Volatile private var enabled = false
    @Volatile private var recordingUid = -1
    @Volatile private var stopFlag = false
    private var worker: Thread? = null

    /** Apply the current setting: turn capture on or off. Idempotent; safe to call from any state. */
    @Synchronized
    fun refresh(context: Context) {
        val on = Settings(context).msgrRecEnabled
        if (on) enable(context) else disable()
    }

    @Synchronized
    private fun enable(context: Context) {
        appCtx = context.applicationContext
        if (enabled) return
        val manager = appCtx!!.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am = manager
        val armed = VoipAudioPolicy.arm()
        if (!armed) {
            LogFile.append("$TAG: не удалось заармить политику (нет CAPTURE_VOICE_COMMUNICATION_OUTPUT? priv-app allowlist не применился?)")
            return
        }
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
        LogFile.append("$TAG: включено (armed, событийный детект)")
        // A call may already be active when the feature is switched on — check once.
        runCatching { evaluate(manager.activePlaybackConfigurations) }
    }

    @Synchronized
    private fun disable() {
        if (!enabled && worker == null) { VoipAudioPolicy.disarm(); return }
        stopCurrent()
        callback?.let { cb -> runCatching { am?.unregisterAudioPlaybackCallback(cb) } }
        callback = null
        cbThread?.quitSafely(); cbThread = null
        VoipAudioPolicy.disarm()
        enabled = false
        LogFile.append("$TAG: выключено")
    }

    /** Decide from the live playback configs whether a whitelisted messenger call is up. */
    @Synchronized
    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val ctx = appCtx ?: return
        val whitelist = Settings(ctx).msgrRecApps
        // Find an active VOICE_COMMUNICATION playback owned by a whitelisted app.
        var hitUid = -1
        var hitPkg: String? = null
        for (c in configs) {
            if (c.audioAttributes?.usage != USAGE_VOICE_COMMUNICATION) continue
            val uid = clientUid(c)
            if (uid < 10000) continue
            val pkg = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull { it in whitelist } ?: continue
            hitUid = uid; hitPkg = pkg; break
        }
        if (hitPkg != null && worker == null) {
            startCurrent(ctx, hitUid, appLabel(ctx, hitPkg))
        } else if (hitPkg == null && worker != null) {
            stopCurrent()
        }
    }

    private fun startCurrent(ctx: Context, uid: Int, label: String) {
        recordingUid = uid
        stopFlag = false
        val startedAt = System.currentTimeMillis()
        worker = Thread({ recordSession(ctx, label, startedAt) }, "msgr-rec").apply { start() }
        LogFile.append("$TAG: старт записи — $label (uid=$uid)")
    }

    private fun stopCurrent() {
        val w = worker ?: return
        stopFlag = true
        runCatching { w.join(4000) }
        worker = null
        recordingUid = -1
    }

    /** Far (peer, loopback sink) + near (MIC) summed to a mono 48 kHz WAV, then filed in the journal. */
    private fun recordSession(ctx: Context, label: String, startedAt: Long) = runCatching {
        val far = VoipAudioPolicy.createSink()
        val nb0 = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val near = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, nb0 * 2)
        }.getOrNull()
        if (far == null && near == null) { LogFile.append("$TAG: нет ни sink, ни mic — запись отменена"); return@runCatching }

        File(OUT_DIR).mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
        val file = File(OUT_DIR, "${safe(label)}_$stamp.wav")
        val raf = RandomAccessFile(file, "rw"); raf.setLength(0); writeWavHeader(raf, 0)
        var dataLen = 0L
        runCatching { far?.startRecording() }; runCatching { near?.startRecording() }

        val n = 1024
        val nbuf = ShortArray(n); val fbuf = ShortArray(n); val out = ByteArray(n * 2)
        while (!stopFlag) {
            val rn = near?.read(nbuf, 0, n) ?: 0
            val rf = far?.read(fbuf, 0, n) ?: 0
            val frames = maxOf(rn, rf)
            if (frames <= 0) { Thread.sleep(5); continue }
            var bi = 0
            for (i in 0 until frames) {
                val a = if (i < rn) nbuf[i].toInt() else 0
                val b = if (i < rf) fbuf[i].toInt() else 0
                val mix = (a + b).coerceIn(-32768, 32767)
                out[bi++] = (mix and 0xff).toByte(); out[bi++] = ((mix shr 8) and 0xff).toByte()
            }
            raf.write(out, 0, bi); dataLen += bi
        }
        runCatching { near?.stop() }; runCatching { far?.stop() }
        runCatching { near?.release() }; runCatching { far?.release() }
        patchWavSizes(raf, dataLen); raf.close()

        val durMs = dataLen / (RATE.toLong() * 2) * 1000
        if (dataLen > RATE) { // at least ~0.5s of audio — ignore blips
            runCatching {
                HistoryDb.get(ctx).amRecInsert(
                    number = null, name = label, ts = startedAt, durationMs = durMs,
                    file = file.absolutePath, reason = "messenger", heard = false,
                )
            }
            LogFile.append("$TAG: сохранено ${file.name} (${durMs / 1000}s) → журнал")
        } else { runCatching { file.delete() } }
    }.onFailure { LogFile.append("$TAG: recordSession: ${it.message}") }

    /** AudioPlaybackConfiguration.getClientUid() is hidden but readable by a system app. */
    private fun clientUid(c: AudioPlaybackConfiguration): Int = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("getClientUid").invoke(c) as Int
    }.getOrDefault(-1)

    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifEmpty { "Messenger" }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val ch = 1; val bits = 16; val byteRate = RATE * ch * bits / 8
        raf.write("RIFF".toByteArray()); le(raf, 36 + dataLen); raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray()); le(raf, 16); le16(raf, 1); le16(raf, ch); le(raf, RATE)
        le(raf, byteRate); le16(raf, ch * bits / 8); le16(raf, bits); raf.write("data".toByteArray()); le(raf, dataLen)
    }
    private fun patchWavSizes(raf: RandomAccessFile, dataLen: Long) {
        raf.seek(4); le(raf, (36 + dataLen).toInt()); raf.seek(40); le(raf, dataLen.toInt())
    }
    private fun le(raf: RandomAccessFile, v: Int) = raf.write(byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
    private fun le16(raf: RandomAccessFile, v: Int) = raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
}
