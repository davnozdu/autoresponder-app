package com.davnozdu.autoresponder.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Срабатывание ежедневного бэкапа: выполнить копию и запланировать следующий день. */
class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val app = context.applicationContext
        // Private receiver: only system BOOT_COMPLETED and our own PendingIntent.
        // External apps must not be able to request repeated disk snapshots.
        if (action == Backup.ACTION || action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            Thread {
                try {
                    if (action == Backup.ACTION) Backup.run(app, scheduled = true)
                    Backup.schedule(app)  // (пере)планируем следующий
                    // Сводка больше не по будильнику, а по выключению DND. Снимаем тот,
                    // что мог остаться от прошлой версии, — другого BOOT_COMPLETED нет.
                    com.davnozdu.autoresponder.notif.Digest.cancelLegacyAlarm(app)
                    Heartbeat.tick(app)
                } catch (_: Exception) {
                } finally { pending.finish() }
            }.start()
        }
    }
}
