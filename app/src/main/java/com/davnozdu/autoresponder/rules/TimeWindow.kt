package com.davnozdu.autoresponder.rules

import java.util.Calendar

/**
 * Окно внутри суток в минутах от полуночи.
 *
 * Отдельно от [ClosedState] потому, что таких окон в приложении уже два — расписание
 * «закрыто» и тихий час, — а правило у них одно и то же, включая переход через полночь.
 */
object TimeWindow {

    /** Внутри ли [nowMin] окна `[start, end)`. Равные границы — целые сутки. */
    fun contains(nowMin: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> nowMin in start until end
        else -> nowMin >= start || nowMin < end    // через полночь
    }

    /** Текущее время суток в минутах. */
    fun nowMin(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Ближайший момент, когда часы покажут [min] минут суток (сегодня или завтра). */
    fun next(min: Int, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, min / 60); set(Calendar.MINUTE, min % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}
