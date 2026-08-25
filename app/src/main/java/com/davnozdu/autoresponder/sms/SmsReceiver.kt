package com.davnozdu.autoresponder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.davnozdu.autoresponder.respond.Kind
import com.davnozdu.autoresponder.respond.Responder
import com.davnozdu.autoresponder.store.HistoryLogger

/** Приём входящих SMS. RCS сюда не попадает (обрабатывается отдельно, позже). */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Многосегментное SMS склеиваем в один текст.
        val sender = messages[0].displayOriginatingAddress
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }

        HistoryLogger.record(context, sender, "sms", "in", body)
        Responder.handle(context, sender, body, Kind.SMS)
    }
}
