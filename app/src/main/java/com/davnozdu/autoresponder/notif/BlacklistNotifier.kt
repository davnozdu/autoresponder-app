package com.davnozdu.autoresponder.notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.store.HistoryDb
import java.util.Calendar

/** Уведомления о попытках связи из чёрного списка — с гибким таймингом. */
object BlacklistNotifier {

    fun record(context: Context, number: String?, name: String?, channel: String) {
        val s = Settings(context)
        if (!s.notificationsEnabled || s.blNotifMode == 0) return
        HistoryDb.get(context).blPendingAdd(number, name, channel)
        if (s.blAlarmSet) return
        val at = when (s.blNotifMode) {
            2 -> nextDaily(s.blDailyTimeMin)
            else -> System.currentTimeMillis() + s.blNotifDelayMin.coerceAtLeast(1) * 60_000L
        }
        setAlarm(context, at)
        s.blAlarmSet = true
    }

    fun onAlarm(context: Context) {
        val s = Settings(context)
        val db = HistoryDb.get(context)
        val n = db.blPendingCount()
        if (n > 0 && s.notificationsEnabled) {
            AutoNotifications.showBlacklist(context, n, db.blPendingNames().take(6).joinToString(", "))
        }
        db.blPendingClear()
        s.blAlarmSet = false
    }

    private fun setAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 20,
            Intent(context, NotifActionReceiver::class.java).setAction(AutoNotifications.ACT_BL_NOTIFY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun nextDaily(min: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, min / 60); set(Calendar.MINUTE, min % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}
