package com.davnozdu.autoresponder.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.davnozdu.autoresponder.data.EventLog

/** One finite waveform per call: no timers, polling or wake locks. */
internal class AnswerMachineVibration(private val context: Context) {
    private var closed = false
    private var vibrating = false
    private val vibrator = context.getSystemService(Vibrator::class.java)

    @Synchronized
    fun start(seconds: Int) {
        // stop() is terminal, including an IDLE/DND event racing with answerCall().
        if (closed || vibrating || !dndAllowsVibration() || vibrator?.hasVibrator() != true) return
        val pulses = (seconds.coerceIn(1, 330) + 4) / 5
        val timings = LongArray(pulses * 4 + 1)
        for (i in 0 until pulses) {
            timings[i * 4 + 1] = 300
            timings[i * 4 + 2] = 200
            timings[i * 4 + 3] = 300
            timings[i * 4 + 4] = 4200
        }
        try {
            // Respect Android's call/ringer and DND policies; never use bypass flags.
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            vibrating = true
            EventLog(context).add("AM: вибрация ожидания включена")
        } catch (e: Exception) {
            EventLog(context).add("AM: вибрация недоступна (${e.message})")
        }
    }

    fun dndAllowsVibration(): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    @Synchronized
    fun stop() {
        closed = true
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
    }
}
