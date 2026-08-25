package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.data.EventLog

/**
 * v2-диагностика: логирует входящие уведомления мессенджеров (Google Messages/RCS,
 * WhatsApp, Telegram) — отправитель, текст, наличие кнопки «Ответить» с RemoteInput.
 * Пока НЕ отвечает — сначала подтверждаем доступ и структуру на живых данных.
 */
class NotifListenerService : NotificationListenerService() {

    private val watched = setOf(
        "com.google.android.apps.messaging",   // SMS + RCS
        "com.whatsapp", "com.whatsapp.w4b",     // WhatsApp / Business
        "org.telegram.messenger"                // Telegram
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            if (pkg !in watched) return
            val n = sbn.notification ?: return
            // пропускаем сводки групп и «идущие» (ongoing) уведомления
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "?"
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

            // ищем reply-экшен с RemoteInput
            var replyable = false
            n.actions?.forEach { a ->
                a.remoteInputs?.forEach { _ -> replyable = true }
            }
            val short = pkg.substringAfterLast('.')
            EventLog(this).add("NOTIF[$short] from='${title.take(24)}' reply=$replyable text='${text.take(40)}'")
        } catch (e: Exception) {
            EventLog(this).add("NOTIF error: ${e.message}")
        }
    }
}
