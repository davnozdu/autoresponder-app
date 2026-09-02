package com.davnozdu.autoresponder.notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.rules.TimeWindow
import com.davnozdu.autoresponder.store.HistoryDb

/**
 * Утренняя сводка: что было ночью и кому надо ответить самому.
 *
 * Приложение отвечает вместо владельца, и в этом же его слабость: утром никто не знает,
 * кто звонил и чей вопрос остался без живого ответа. Сводка приходит один раз, в рабочее
 * время (по умолчанию 8:00) — ночью телефон не трогаем, ради этого всё и затевалось.
 */
object Digest {

    const val ACTION = "com.davnozdu.autoresponder.DAILY_DIGEST"
    private const val REQ = 32

    /** Окно сводки — сутки до момента её показа. */
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L

    fun onAlarm(context: Context) {
        val s = Settings(context)
        if (s.digestEnabled) show(context, s)
        schedule(context)          // следующий день
    }

    fun show(context: Context, s: Settings = Settings(context)) {
        val db = HistoryDb.get(context)
        val from = System.currentTimeMillis() - WINDOW_MS
        val calls = db.countIncoming(from, listOf("call"))
        val msgs = db.countIncoming(from) - calls
        val answered = db.countAuto(from)
        val pending = db.needsAnswer(from)
        if (calls == 0 && msgs == 0 && pending.isEmpty()) return   // тихая ночь — молчим

        val who = pending.take(5).joinToString(", ") { it.name ?: it.number }
        AutoNotifications.showDigest(context,
            title = "За сутки: $calls звонков, $msgs сообщений",
            text = if (pending.isEmpty()) "Автоответов: $answered. Все ответы даны."
                   else "Требуют ответа: ${pending.size} — $who",
            pending = pending.size)
        EventLog(context).add(
            "Сводка: звонков=$calls, сообщений=$msgs, авто=$answered, требуют ответа=${pending.size}")
    }

    fun schedule(context: Context) {
        val s = Settings(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (!s.digestEnabled) { am.cancel(pi(context)); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TimeWindow.next(s.digestHour * 60), pi(context))
    }

    private fun pi(context: Context): PendingIntent {
        val i = Intent(context, NotifActionReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(context, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
