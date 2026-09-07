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
            // Сводка теперь приходит при выключении DND; старый будильник — снимаем.
            Digest.ACTION -> Digest.cancelLegacyAlarm(context)
            com.davnozdu.autoresponder.store.Heartbeat.ACTION -> {
                com.davnozdu.autoresponder.store.Heartbeat.tick(context)
                // Тот же будильник ведёт журнал: раз в десять минут подбираем исходящие,
                // которые робот не отправлял (SMS владельца, переписка в мессенджерах).
                // Отдельного будильника заводить незачем, а этот переживает Doze и
                // перезагрузку. Работа с БД и файлами — не на потоке ресивера.
                val app = context.applicationContext
                val pending = goAsync()
                Thread {
                    try {
                        com.davnozdu.autoresponder.store.SmsWatcher.drain(app)
                        com.davnozdu.autoresponder.store.MsgrBridge.sync(app)
                    } catch (_: Exception) {
                    } finally { pending.finish() }
                }.start()
            }
        }
    }
}
