package com.davnozdu.autoresponder.rules

import android.content.Context
import com.davnozdu.autoresponder.data.Settings
import java.util.Calendar

/** Решение «включать ли интерактивный скрининг для этого звонка» — своё расписание,
 *  независимое от расписания «закрыто» (см. [ClosedState]). Зеркалит структуру
 *  [ClosedState.closedBySchedule] (режим «рабочие часы/дни»), но с обратной полярностью:
 *  тут спрашиваем «активно ли», а не «закрыто ли». */
object ScreeningPolicy {

    fun shouldScreen(enabled: Boolean, inWindow: Boolean, skip: Boolean): Boolean =
        enabled && inWindow && !skip

    fun isInWindow(context: Context, s: Settings): Boolean {
        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return activeBySchedule(cur, now.get(Calendar.DAY_OF_WEEK),
            s.screeningWorkDaysMask, s.screeningStartMin, s.screeningEndMin)
    }

    /** Чистая часть — ни Context, ни Settings, ни системных часов, только числа. Вынесено
     *  ради юнит-теста, как и у [ClosedState.closedBySchedule].
     *  @param dayOfWeek как в [Calendar.DAY_OF_WEEK] (вс = 1) */
    fun activeBySchedule(nowMin: Int, dayOfWeek: Int, workDaysMask: Int,
                          workStart: Int, workEnd: Int): Boolean {
        val isWorkDay = (workDaysMask and (1 shl dayOfWeek)) != 0
        val inHours = nowMin in workStart until workEnd
        return isWorkDay && inHours
    }
}
