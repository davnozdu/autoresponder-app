package com.davnozdu.autoresponder.notif

import android.content.Context
import com.davnozdu.autoresponder.data.Settings

/**
 * Живая статистика текущего сеанса «Не беспокоить».
 *
 * Постоянное уведомление раньше говорило только «работает» — по нему нельзя было понять,
 * пропустил ли ты за вечер один звонок или пятнадцать. Теперь оно показывает счётчики.
 *
 * Счётчики тикают В МОМЕНТ события: процесс в это время и так разбужен звонком или
 * сообщением, поэтому ни таймера, ни периодического опроса БД для уведомления не нужно —
 * телефон не будится ради перерисовки текста. Хранятся в SharedPreferences, чтобы
 * пережить перезапуск процесса: сеанс DND длится часами, а система вправе выгрузить
 * приложение между звонками.
 */
object DndStats {

    /** Начало сеанса DND — счётчики с нуля. */
    fun startSession(context: Context) { Settings(context).resetDndCounters() }

    /**
     * Событие произошло. Вызывается из [com.davnozdu.autoresponder.store.HistoryLogger] —
     * через него проходят все каналы, и звонки, и авто-ответы.
     */
    fun onEvent(context: Context, channel: String, direction: String, auto: Boolean) {
        val app = context.applicationContext
        val s = Settings(app)
        if (!s.dndWasOn) return          // вне сеанса считать нечего
        when {
            direction == "in" && channel == "call" -> s.dndInCalls = s.dndInCalls + 1
            direction == "in" -> s.dndInMsgs = s.dndInMsgs + 1
            direction == "out" && auto -> s.dndAutoReplies = s.dndAutoReplies + 1
            else -> return               // ручной ответ владельца — не наша статистика
        }
        if (s.notificationsEnabled && s.enabled) AutoNotifications.showDndActive(app, s)
    }

    /** Текст для постоянного уведомления: что уже случилось за сеанс. */
    fun line(s: Settings): String {
        val calls = s.dndInCalls
        val msgs = s.dndInMsgs
        val auto = s.dndAutoReplies
        if (calls == 0 && msgs == 0) return "Пока тихо: ни звонков, ни сообщений"
        return "${plural(calls, "звонок", "звонка", "звонков")}, " +
               "${plural(msgs, "сообщение", "сообщения", "сообщений")}, " +
               "ответов: $auto"
    }

    /** Русское склонение после числа: 1 звонок, 2 звонка, 5 звонков. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val form = when {
            n % 100 in 11..14 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
        return "$n $form"
    }
}
