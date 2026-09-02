package com.davnozdu.autoresponder.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.rules.AutoReplyState

/** Обработка кнопок постоянного уведомления DND. */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AutoNotifications.ACT_PAUSE_NEXT -> { AutoReplyState.pauseUntilNextDnd(context); AutoNotifications.showDndActive(context) }
            AutoNotifications.ACT_PAUSE_REBOOT -> { AutoReplyState.pauseUntilReboot(context); AutoNotifications.showDndActive(context) }
            AutoNotifications.ACT_DISABLE -> {
                Settings(context).enabled = false; AutoReplyState.resume(context); AutoNotifications.cancelDnd(context)
            }
            AutoNotifications.ACT_BL_NOTIFY -> BlacklistNotifier.onAlarm(context)
            AutoNotifications.ACT_QUIET_FLUSH -> com.davnozdu.autoresponder.respond.QuietHours.onAlarm(context)
            Digest.ACTION -> Digest.onAlarm(context)
            com.davnozdu.autoresponder.store.Heartbeat.ACTION ->
                com.davnozdu.autoresponder.store.Heartbeat.tick(context)
        }
    }
}
