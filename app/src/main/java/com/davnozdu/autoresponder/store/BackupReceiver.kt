package com.davnozdu.autoresponder.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Срабатывание ежедневного бэкапа: выполнить копию и запланировать следующий день. */
class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val app = context.applicationContext
        // Ресивер экспортирован ради BOOT_COMPLETED, поэтому сюда может прилететь явный интент
        // от постороннего приложения — обрабатываем только два известных действия.
        if (action == Backup.ACTION || action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            Thread {
                try {
                    if (action == Backup.ACTION) Backup.run(app)
                    Backup.schedule(app)  // (пере)планируем следующий
                } catch (_: Exception) {
                } finally { pending.finish() }
            }.start()
        }
    }
}
