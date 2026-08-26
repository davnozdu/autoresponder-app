package com.davnozdu.autoresponder.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.respond.SmsSender

/** Статус отправки SMS: при сбое — лог и повтор (до 2 попыток). */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.davnozdu.autoresponder.SMS_SENT") return
        if (resultCode == Activity.RESULT_OK) return  // доставлено оператору — ок

        val number = intent.getStringExtra("number") ?: return
        val text = intent.getStringExtra("text") ?: return
        val subId = intent.getIntExtra("subId", -1)
        val attempt = intent.getIntExtra("attempt", 0)
        val app = context.applicationContext          // ресивер после onReceive неактивен — берём app-контекст
        val log = EventLog(app)
        if (attempt < 2) {
            log.add("SMS $number — сбой отправки (код $resultCode), повтор #${attempt + 1}")
            // goAsync() удерживает процесс живым на время паузы (иначе его могут убить и повтор не выполнится).
            val pending = goAsync()
            Thread {
                try {
                    Thread.sleep(4000)
                    SmsSender.send(app, number, text, subId, attempt + 1)
                } catch (_: Exception) {
                } finally { pending.finish() }
            }.start()
        } else {
            log.add("SMS $number — не доставлено после ${attempt + 1} попыток (код $resultCode)")
        }
    }
}
