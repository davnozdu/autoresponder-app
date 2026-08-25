package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.store.HistoryLogger

/** Ловит входящие сообщения выбранных мессенджеров через уведомления. */
class NotifListenerService : NotificationListenerService() {

    private val messagesPkg = "com.google.android.apps.messaging"

    private fun tagFor(pkg: String): String = when (pkg) {
        messagesPkg -> "rcs"
        "com.whatsapp", "com.whatsapp.w4b" -> "whatsapp"
        "org.telegram.messenger" -> "telegram"
        else -> try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)).toString().lowercase()
        } catch (e: Exception) { pkg.substringAfterLast('.') }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            if (pkg !in Settings(this).monitoredApps) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val history = n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            if (history != null && history.isNotEmpty()) return

            val (sender, text, isGroup) = NotifResponder.extract(n) ?: return
            if (text.isBlank()) return

            val channel = if (pkg == messagesPkg) Channel.MESSAGES else Channel.MESSENGER
            val tag = tagFor(pkg)
            val hasReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            EventLog(this).add("NOTIF[$tag] from='${sender.take(20)}' group=$isGroup reply=$hasReply text='${text.take(36)}'")
            // История входящего мессенджера — всегда (не только когда «закрыто»); группы не пишем.
            if (channel == Channel.MESSENGER && !isGroup)
                HistoryLogger.record(this, sender, tag, "in", text)
            NotifResponder.handle(this, sbn, sender, text, channel, tag, isGroup, hasReply)
        } catch (e: Exception) {
            EventLog(this).add("NOTIF error: ${e.message}")
        }
    }
}
