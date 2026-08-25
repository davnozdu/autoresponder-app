package com.davnozdu.autoresponder.notif

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Заготовка под WhatsApp/Telegram/RCS. В v1 логика не подключена —
 * canvas для будущей обработки уведомлений мессенджеров.
 */
class NotifListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // TODO(v2): читать текст из sbn, отвечать через RemoteInput / SMS-фолбэк.
    }
}
