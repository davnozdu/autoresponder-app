package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.data.EventLog

/** Ловит входящие сообщения Google Messages(RCS)/WhatsApp/Telegram через уведомления. */
class NotifListenerService : NotificationListenerService() {

    private fun channelFor(pkg: String): Channel? = when (pkg) {
        "com.google.android.apps.messaging" -> Channel.MESSAGES
        "com.whatsapp", "com.whatsapp.w4b" -> Channel.WHATSAPP
        "org.telegram.messenger" -> Channel.TELEGRAM
        else -> null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            val channel = channelFor(pkg) ?: return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            // Уже отвечали в этом уведомлении (нами или вручную) — не зацикливаемся.
            val history = n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            if (history != null && history.isNotEmpty()) return

            val (sender, text, isGroup) = NotifResponder.extract(n) ?: return
            if (text.isBlank()) return

            val hasReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            EventLog(this).add(
                "NOTIF[${channel.name.lowercase()}] from='${sender.take(20)}' group=$isGroup reply=$hasReply text='${text.take(36)}'"
            )
            NotifResponder.handle(this, sbn, sender, text, channel, isGroup, hasReply)
        } catch (e: Exception) {
            EventLog(this).add("NOTIF error: ${e.message}")
        }
    }
}
