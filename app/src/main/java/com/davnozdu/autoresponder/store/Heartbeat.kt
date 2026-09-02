package com.davnozdu.autoresponder.store

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.notif.NotifActionReceiver
import java.io.File

/**
 * Признак жизни для KernelSU-модуля.
 *
 * Прав, ролей и разрешений может быть достаточно, а процесс при этом убит менеджером
 * питания OxygenOS: снаружи всё «настроено», а звонки и сообщения не обрабатываются.
 * Узнать об этом можно было только по жалобе клиента.
 *
 * Приложение раз в [PERIOD_MS] трогает файл в своём каталоге (root его читает,
 * разрешений для этого не нужно никому). Watchdog смотрит на время изменения:
 * файл устарел — процесс поднимают заново. Если приложение убито force-stop'ом,
 * система снимает и его alarm'ы, поэтому файл перестаёт обновляться сам собой —
 * ровно то поведение, которое нужно.
 */
object Heartbeat {

    const val ACTION = "com.davnozdu.autoresponder.HEARTBEAT"
    const val PERIOD_MS = 10 * 60 * 1000L
    private const val REQ = 33

    fun file(context: Context) = File(context.filesDir, "heartbeat")

    /** Отметиться и завести следующий будильник. */
    fun tick(context: Context) {
        touch(context)
        schedule(context)
    }

    fun touch(context: Context) {
        try {
            val s = com.davnozdu.autoresponder.data.Settings(context)
            val listener = com.davnozdu.autoresponder.notif.NotifListenerService.isConnected
            file(context).writeText(
                "${System.currentTimeMillis()}\nenabled=${if (s.enabled) 1 else 0}\n" +
                "listener=${if (listener) 1 else 0}\n")
        } catch (_: Exception) { /* нет места или отозван доступ — не наша забота */ }
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + PERIOD_MS, pi(context))
    }

    private fun pi(context: Context): PendingIntent {
        val i = Intent(context, NotifActionReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(context, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
