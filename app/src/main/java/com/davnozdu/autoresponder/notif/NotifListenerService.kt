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

    private val dndReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { AutoNotifications.onDndChanged(c) }
    }

    override fun onListenerConnected() {
        instance = this
        AutoNotifications.ensureChannels(this)
        try {
            registerReceiver(dndReceiver, android.content.IntentFilter(
                android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                android.content.Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
        AutoNotifications.onDndChanged(this)  // синхронизировать текущее состояние
        // Восстановление после простоя: слушатель мог быть отвязан (падение процесса, ре-бинд
        // watchdog'ом, перезагрузка) — на переподключении система НЕ переигрывает onNotificationPosted,
        // поэтому активно подхватываем ещё свежие непрочитанные. Возрастной фильтр (5 мин) внутри
        // handlePosted отсекает старьё, а персистентный ReplyStore не даёт ответить повторно.
        try { activeNotifications?.forEach { handlePosted(it) } } catch (_: Exception) {}
    }
    override fun onListenerDisconnected() {
        instance = null
        try { unregisterReceiver(dndReceiver) } catch (_: Exception) {}
    }

    companion object {
        @Volatile private var instance: NotifListenerService? = null
        /** Снять уведомление после ответа, чтобы не обрабатывать повторно. */
        fun dismiss(key: String?) {
            if (key == null) return
            try { instance?.cancelNotification(key) } catch (_: Exception) {}
        }
    }

    private fun tagFor(pkg: String): String = when (pkg) {
        messagesPkg -> "rcs"
        "com.whatsapp", "com.whatsapp.w4b" -> "whatsapp"
        "org.telegram.messenger" -> "telegram"
        else -> try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)).toString().lowercase()
        } catch (e: Exception) { pkg.substringAfterLast('.') }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handlePosted(sbn)

    private fun handlePosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            val s = Settings(this)
            if (pkg !in s.monitoredApps) return
            val n = sbn.notification ?: return
            // Игнорируем старые/восстановленные уведомления (после перезагрузки система
            // восстанавливает непрочитанные — на них отвечать нельзя). Только свежие.
            if (System.currentTimeMillis() - sbn.postTime > s.notifMaxAgeMin * 60_000L) return
            // Telegram: только личные чаты — не каналы, не группы (по id канала уведомления).
            if (pkg == "org.telegram.messenger" &&
                !(n.channelId ?: "").contains("private", ignoreCase = true)) return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val history = n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            if (history != null && history.isNotEmpty()) return

            val (sender, text, isGroup) = NotifResponder.extract(n) ?: return
            if (text.isBlank()) return
            // Отсекаем Telegram-ботов (имя оканчивается на "bot").
            if (pkg == "org.telegram.messenger" && sender.trim().lowercase().endsWith("bot")) return

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
