package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.data.EventLog

/**
 * Ловит входящие сообщения мессенджеров через уведомления.
 * Google Messages (SMS+RCS) — обрабатываем и отвечаем. WhatsApp/Telegram — пока только лог.
 */
class NotifListenerService : NotificationListenerService() {

    private val messages = "com.google.android.apps.messaging"
    private val logged = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            if (pkg != messages && pkg !in logged) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

            val pair = NotifResponder.extract(n) ?: return
            val (sender, text) = pair
            if (text.isBlank()) return

            val hasReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            val short = pkg.substringAfterLast('.')
            EventLog(this).add("NOTIF[$short] from='${sender.take(24)}' reply=$hasReply text='${text.take(40)}'")

            if (pkg == messages) {
                NotifResponder.handle(this, sbn, sender, text)
            }
        } catch (e: Exception) {
            EventLog(this).add("NOTIF error: ${e.message}")
        }
    }
}
