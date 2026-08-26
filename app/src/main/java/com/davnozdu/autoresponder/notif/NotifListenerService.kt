package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings

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
        com.davnozdu.autoresponder.store.Backup.schedule(this)  // ежедневный бэкап БД
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
            // Дальше уведомление НАШЕ (пакет отслеживается) — каждый отказ логируем,
            // иначе «в журнале пусто» невозможно отличить от «уведомление не пришло».
            val tag0 = tagFor(pkg)
            fun drop(why: String) { EventLog(this).add("NOTIF[$tag0] пропуск: $why") }

            // Игнорируем старые/восстановленные уведомления (после перезагрузки система
            // восстанавливает непрочитанные — на них отвечать нельзя). Только свежие.
            val ageMin = (System.currentTimeMillis() - sbn.postTime) / 60_000L
            if (System.currentTimeMillis() - sbn.postTime > s.notifMaxAgeMin * 60_000L) {
                drop("старое уведомление ($ageMin мин > ${s.notifMaxAgeMin})"); return
            }
            // Telegram: только личные чаты — не каналы, не группы (по id канала уведомления).
            if (pkg == "org.telegram.messenger" &&
                !(n.channelId ?: "").contains("private", ignoreCase = true)) {
                drop("Telegram: не личный чат (channel=${n.channelId})"); return
            }
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { drop("сводка группы уведомлений"); return }
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) { drop("постоянное уведомление"); return }
            val history = n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            if (history != null && history.isNotEmpty()) { drop("уже отвечено из уведомления"); return }

            val extracted = NotifResponder.extract(n)
            if (extracted == null) { drop("не удалось разобрать (нет MessagingStyle/title/text)"); return }
            val (sender, text, isGroup) = extracted
            if (text.isBlank()) { drop("пустой текст (вложение/стикер?)"); return }
            // Отсекаем Telegram-ботов (имя оканчивается на "bot").
            if (pkg == "org.telegram.messenger" && sender.trim().lowercase().endsWith("bot")) return

            val channel = if (pkg == messagesPkg) Channel.MESSAGES else Channel.MESSENGER
            val tag = tagFor(pkg)
            val hasReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            EventLog(this).add("NOTIF[$tag] from='${sender.take(20)}' group=$isGroup reply=$hasReply text='${text.take(36)}'")
            // Историю входящего пишет NotifResponder ПОСЛЕ дедупа: WhatsApp постит одно
            // сообщение несколькими уведомлениями, и запись здесь дублировала его в БД
            // (а значит и в контексте LLM).
            NotifResponder.handle(this, sbn, sender, text, channel, tag, isGroup, hasReply)
        } catch (e: Exception) {
            EventLog(this).add("NOTIF error: ${e.message}")
        }
    }
}
