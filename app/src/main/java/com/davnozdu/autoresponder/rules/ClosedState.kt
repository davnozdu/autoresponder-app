package com.davnozdu.autoresponder.rules

import android.app.NotificationManager
import android.content.Context
import com.davnozdu.autoresponder.data.Settings
import java.util.Calendar

/** Определяет, «закрыто» ли сейчас и ПОЧЕМУ: системный DND или собственное расписание. */
object ClosedState {

    /** Причина закрытия или null, если открыто. */
    fun reason(context: Context, s: Settings): String? {
        if (s.triggerOnDnd && isDndOn(context)) return "DND"
        if (s.triggerOnSchedule && inSchedule(s)) return "расписание"
        return null
    }

    fun isClosed(context: Context, s: Settings): Boolean = reason(context, s) != null

    fun isDndOn(context: Context): Boolean =
        dndFilter(context) > NotificationManager.INTERRUPTION_FILTER_ALL

    /** Текущий фильтр прерываний: 1=ALL(выкл),2=PRIORITY,3=NONE,4=ALARMS. */
    fun dndFilter(context: Context): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter
    }

    private fun inSchedule(s: Settings): Boolean {
        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (s.scheduleMode == 1) {
            // Рабочие часы/дни: «закрыто», если НЕ рабочий день ИЛИ вне рабочих часов.
            val dow = now.get(Calendar.DAY_OF_WEEK)
            val isWorkDay = (s.workDaysMask and (1 shl dow)) != 0
            val inHours = cur in s.workStartMin until s.workEndMin
            return !(isWorkDay && inHours)
        }
        val start = s.scheduleStartMin
        val end = s.scheduleEndMin
        // Одинаковые границы = окно на целые сутки (раньше «закрыто» не наступало никогда).
        if (start == end) return true
        return if (start < end) cur in start until end
               else cur >= start || cur < end
    }
}
