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
        return closedBySchedule(
            mode = s.scheduleMode, nowMin = cur, dayOfWeek = now.get(Calendar.DAY_OF_WEEK),
            workDaysMask = s.workDaysMask, workStart = s.workStartMin, workEnd = s.workEndMin,
            windowStart = s.scheduleStartMin, windowEnd = s.scheduleEndMin)
    }

    /**
     * Чистая часть расписания: ни Context, ни Settings, ни системных часов — только числа.
     * Вынесено ради юнит-теста: окно через полночь и совпадающие границы — ровно те места,
     * где ошибка означает молчание для живого клиента и замечается только по жалобе.
     *
     * @param mode 1 — рабочие часы/дни («закрыто» вне них), иначе окно «закрыто» start..end
     * @param dayOfWeek как в [Calendar.DAY_OF_WEEK] (вс = 1)
     */
    fun closedBySchedule(mode: Int, nowMin: Int, dayOfWeek: Int, workDaysMask: Int,
                         workStart: Int, workEnd: Int,
                         windowStart: Int, windowEnd: Int): Boolean {
        if (mode == 1) {
            // Рабочие часы/дни: «закрыто», если НЕ рабочий день ИЛИ вне рабочих часов.
            val isWorkDay = (workDaysMask and (1 shl dayOfWeek)) != 0
            val inHours = nowMin in workStart until workEnd
            return !(isWorkDay && inHours)
        }
        // Одинаковые границы = окно на целые сутки (раньше «закрыто» не наступало никогда),
        // переход через полночь — там же, в TimeWindow: тем же правилом живёт тихий час.
        return TimeWindow.contains(nowMin, windowStart, windowEnd)
    }
}
