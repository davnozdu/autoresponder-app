package com.davnozdu.autoresponder.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import com.davnozdu.autoresponder.data.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short pulses only during this call; no work or wake locks between calls. */
internal class AnswerMachineVibration(private val context: Context) {
    private var closed = false
    private var vibrating = false
    private var pulseJob: Job? = null
    private val vibrator = context.getSystemService(Vibrator::class.java)

    @Synchronized
    fun start(seconds: Int, scope: CoroutineScope) {
        // stop() is terminal, including an IDLE/DND event racing with answerCall().
        if (closed || vibrating || !dndAllowsVibration() || vibrator?.hasVibrator() != true) return
        val deadline = SystemClock.elapsedRealtime() + seconds.coerceIn(1, 330) * 1000L
        // The initial notification vibrates first (and is mirrored to the watch).
        // Use short subsequent pulses so other notifications cannot cancel all reminders.
        pulseJob = scope.launch {
            delay(5000)
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!pulse()) break
                delay(5000)
            }
        }
        EventLog(context).add("AM: вибрация ожидания включена")
    }

    @Synchronized
    private fun pulse(): Boolean {
        if (closed || !dndAllowsVibration()) return false
        try {
            // Respect Android's call/ringer and DND policies; never use bypass flags.
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            vibrating = true
            return true
        } catch (e: Exception) {
            EventLog(context).add("AM: вибрация недоступна (${e.message})")
            return false
        }
    }

    fun dndAllowsVibration(): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    @Synchronized
    fun stop() {
        closed = true
        pulseJob?.cancel()
        pulseJob = null
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
    }
}
