package com.davnozdu.autoresponder.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.R
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.rules.AutoReplyState
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.ui.HistoryActivity

object AutoNotifications {
    const val CH_DND = "autoresp_dnd"
    const val CH_SUMMARY = "autoresp_summary"
    const val CH_BLACKLIST = "autoresp_blacklist"
    const val ID_DND = 1001
    const val ID_SUMMARY = 1002

    const val ACT_PAUSE_NEXT = "com.davnozdu.autoresponder.PAUSE_NEXT_DND"
    const val ACT_PAUSE_REBOOT = "com.davnozdu.autoresponder.PAUSE_REBOOT"
    const val ACT_DISABLE = "com.davnozdu.autoresponder.DISABLE"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun ensureChannels(context: Context) {
        val m = nm(context) ?: return
        m.createNotificationChannel(NotificationChannel(CH_DND, "Автоответ активен",
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        m.createNotificationChannel(NotificationChannel(CH_SUMMARY, "Сводка автоответа",
            NotificationManager.IMPORTANCE_DEFAULT))
        m.createNotificationChannel(NotificationChannel(CH_BLACKLIST, "Чёрный список",
            NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Реакция на смену режима DND. */
    fun onDndChanged(context: Context) {
        val app = context.applicationContext
        val s = Settings(app)
        val dndOn = ClosedState.isDndOn(app)
        if (dndOn && !s.dndWasOn) {
            s.dndWasOn = true; s.lastDndOnTime = System.currentTimeMillis()
            if (s.notificationsEnabled) showDndActive(app)
        } else if (!dndOn && s.dndWasOn) {
            s.dndWasOn = false
            AutoReplyState.onDndOff(app)
            cancelDnd(app)
            if (s.notificationsEnabled) showSummary(app, s.lastDndOnTime)
        }
    }

    private fun action(context: Context, act: String, id: Int): PendingIntent {
        val i = Intent(context, NotifActionReceiver::class.java).setAction(act)
        return PendingIntent.getBroadcast(context, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showDndActive(context: Context) {
        ensureChannels(context)
        val paused = AutoReplyState.isPaused(context)
        val n = androidx.core.app.NotificationCompat.Builder(context, CH_DND)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(if (paused) "Автоответ на паузе" else "Автоответ работает")
            .setContentText(if (paused) "Пауза активна" else "Отвечаю на звонки и сообщения (DND)")
            .setOngoing(true).setSilent(true)
            .addAction(0, "До след. DND", action(context, ACT_PAUSE_NEXT, 1))
            .addAction(0, "До перезагрузки", action(context, ACT_PAUSE_REBOOT, 2))
            .addAction(0, "Выключить", action(context, ACT_DISABLE, 3))
            .build()
        nm(context)?.notify(ID_DND, n)
    }

    fun cancelDnd(context: Context) { nm(context)?.cancel(ID_DND) }

    private fun showSummary(context: Context, from: Long) {
        val db = HistoryDb.get(context)
        val sms = db.countAuto(from, listOf("sms"))
        val calls = db.countAuto(from, listOf("call"))
        val total = db.countAuto(from)
        val msgr = (total - sms - calls).coerceAtLeast(0)
        if (total == 0) return
        val tap = PendingIntent.getActivity(context, 10,
            Intent(context, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(context, CH_SUMMARY)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("Автоответ: сводка за DND")
            .setContentText("Отвечено: $sms SMS, $msgr сообщений, $calls звонков")
            .setContentIntent(tap).setAutoCancel(true).build()
        nm(context)?.notify(ID_SUMMARY, n)
    }
}
