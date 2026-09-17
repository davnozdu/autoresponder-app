package com.davnozdu.autoresponder.notif

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.ui.HistoryActivity

object HandoffNotifications {
    fun show(context: Context, who: String, channel: String) {
        if(!Settings(context).notificationsEnabled) return
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("conversations","Разговоры автоответчика",NotificationManager.IMPORTANCE_LOW))
        val intent=Intent(context,HistoryActivity::class.java).putExtra("who",who).putExtra("channel",channel)
            .putExtra("handoff",true).setData(Uri.parse("autoresp://handoff/"+Uri.encode("$channel:$who")))
        val tap=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { nm.notify("$channel:$who",2102,NotificationCompat.Builder(context,"conversations")
            .setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(who)
            .setContentText("Автоответчик обрабатывает обращение")
            .setContentIntent(tap).addAction(0,"Отвечаю я",tap).setOnlyAlertOnce(true).setAutoCancel(true).build())
        } catch (_:SecurityException) {}
    }
}
