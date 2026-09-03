package com.davnozdu.autoresponder.notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.store.HistoryDb

/**
 * Сводка за сеанс «Не беспокоить»: что случилось, пока телефон молчал, и кому надо
 * ответить самому.
 *
 * Приходит в момент ВЫКЛЮЧЕНИЯ DND, а не в назначенный час. Раньше сводка ждала утра,
 * и к этому времени было уже не разобрать, к какому вечеру она относится; теперь она
 * встречает владельца ровно тогда, когда он снял режим и взял телефон в руки — по горячим
 * следам видно, кто звонил и чей вопрос остался без живого ответа. Побочно это дешевле:
 * ни будильника, ни пробуждения процесса ради проверки времени.
 */
object Digest {

    const val ACTION = "com.davnozdu.autoresponder.DAILY_DIGEST"
    private const val REQ = 32

    /** Окно на случай, когда начало сеанса неизвестно (первый запуск, «Показать сейчас»). */
    private const val FALLBACK_MS = 24 * 60 * 60 * 1000L

    /**
     * Выключатель [Settings.digestEnabled] проверяет вызывающий: кнопка «Показать сейчас»
     * и SMS-команда `digest` должны работать и при выключенной автоматической сводке.
     *
     * @param from начало сеанса DND; 0 — взять сутки назад (ручной показ и SMS-команда).
     */
    fun show(context: Context, s: Settings = Settings(context), from: Long = 0L) {
        val db = HistoryDb.get(context)
        val since = if (from > 0L) from else System.currentTimeMillis() - FALLBACK_MS
        val calls = db.countIncoming(since, listOf("call"))
        val msgs = db.countIncoming(since) - calls
        val answered = db.countAuto(since)
        val pending = db.needsAnswer(since)
        if (calls == 0 && msgs == 0 && pending.isEmpty()) return   // тихий сеанс — молчим

        val who = pending.take(5).joinToString(", ") { it.name ?: it.number }
        val head = if (from > 0L) "За «Не беспокоить»" else "За сутки"
        AutoNotifications.showDigest(context,
            title = "$head: ${DndStats.plural(calls, "звонок", "звонка", "звонков")}, " +
                    DndStats.plural(msgs, "сообщение", "сообщения", "сообщений"),
            text = if (pending.isEmpty()) "Автоответов: $answered. Все ответы даны."
                   else "Автоответов: $answered. Требуют ответа: ${pending.size} — $who",
            pending = pending.size)
        EventLog(context).add(
            "Сводка: звонков=$calls, сообщений=$msgs, авто=$answered, требуют ответа=${pending.size}")
    }

    /**
     * Снять старый будильник сводки. Сама сводка теперь приходит по событию (выключение DND),
     * но у тех, кто обновился с прошлой версии, будильник уже стоит в системе.
     */
    fun cancelLegacyAlarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(context, NotifActionReceiver::class.java).setAction(ACTION)
        am.cancel(PendingIntent.getBroadcast(context, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}
