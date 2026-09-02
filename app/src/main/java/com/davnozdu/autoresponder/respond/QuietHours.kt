package com.davnozdu.autoresponder.respond

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.notif.AutoNotifications
import com.davnozdu.autoresponder.notif.NotifActionReceiver
import com.davnozdu.autoresponder.rules.TimeWindow
import com.davnozdu.autoresponder.store.HistoryDb

/**
 * Тихий час: ночной звонок отклоняется как обычно, а вот авто-SMS в ответ на него
 * придерживается до утра.
 *
 * Клиент, набравший в три часа ночи, чаще всего ошибся номером или звонит из другого
 * часового пояса; ответная SMS будит его в ту же секунду. Утренняя приходит тогда,
 * когда её можно прочитать, и одна на номер — сколько бы раз ночью ни звонили.
 *
 * Ответы на СМС и сообщения мессенджеров не придерживаются: человек написал сам,
 * он не спит, и молчание в ответ выглядело бы поломкой.
 */
object QuietHours {

    /** Сейчас тихий час? */
    fun active(s: Settings, nowMin: Int = TimeWindow.nowMin()): Boolean =
        s.quietHoursOn && TimeWindow.contains(nowMin, s.quietStartMin, s.quietEndMin)

    /**
     * Придержать SMS, если идёт тихий час.
     * @return true — событие отложено, отвечать сейчас не нужно.
     */
    fun holdIfQuiet(context: Context, s: Settings, number: String): Boolean {
        if (!active(s)) return false
        val db = HistoryDb.get(context)
        db.smsHoldAdd(number)
        schedule(context, s)
        EventLog(context).add(
            "CALL $number — тихий час, SMS придержана до ${TimeWindow.fmt(s.quietEndMin)}")
        return true
    }

    /** Утро: разослать то, что накопилось. */
    fun onAlarm(context: Context) {
        val s = Settings(context)
        val db = HistoryDb.get(context)
        val numbers = db.smsHoldNumbers()
        db.smsHoldClear()
        if (numbers.isEmpty()) return
        EventLog(context).add("Тихий час закончился: отвечаю на ${numbers.size} ночных звонков")
        // Через обычный конвейер: лимиты, ЧС и выбор SIM должны сработать как всегда,
        // а тихий час к этому времени уже не активен, и событие пройдёт дальше.
        numbers.forEach { Responder.handle(context, it, null, Kind.CALL) }
    }

    private fun intent(context: Context) =
        Intent(context, NotifActionReceiver::class.java).setAction(AutoNotifications.ACT_QUIET_FLUSH)

    private fun schedule(context: Context, s: Settings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 21, intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Перевзводим на каждый звонок: время конца окна одно и то же, лишнего alarm не будет,
        // а после перезагрузки или force-stop расписание восстановится с первым же звонком.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TimeWindow.next(s.quietEndMin), pi)
    }
}
