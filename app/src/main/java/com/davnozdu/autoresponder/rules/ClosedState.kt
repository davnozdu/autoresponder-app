package com.davnozdu.autoresponder.rules

import android.app.NotificationManager
import android.content.Context
import com.davnozdu.autoresponder.data.Settings
import java.util.Calendar

/** Определяет, «закрыто» ли сейчас: системный DND или собственное расписание. */
object ClosedState {

    fun isClosed(context: Context, s: Settings): Boolean {
        if (s.triggerOnDnd && isDndOn(context)) return true
        if (s.triggerOnSchedule && inSchedule(s)) return true
        return false
    }

    private fun isDndOn(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // ALL(1) = выкл; PRIORITY(2)/NONE(3)/ALARMS(4) = DND включён
        return nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun inSchedule(s: Settings): Boolean {
        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = s.scheduleStartMin
        val end = s.scheduleEndMin
        return if (start <= end) {
            cur in start until end            // окно в пределах суток
        } else {
            cur >= start || cur < end         // окно через полночь (напр. 21:00–09:00)
        }
    }
}
