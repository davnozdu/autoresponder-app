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

        // На разных прошивках subId приезжает в разных extra. Ключи со СЛОТОМ («slot»/«phone»)
        // держим отдельно: слот 0/1 — это не subId, его нужно преобразовать, иначе ответ уйдёт
        // не с той карты.
        val subId = listOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX",
                           "subscription_id", "simId")
            .map { intent.getIntExtra(it, -1) }
            .firstOrNull { it >= 0 }
            ?: listOf("slot", "phone", "android.telephony.extra.SLOT_INDEX")
                .map { intent.getIntExtra(it, -1) }
                .firstOrNull { it >= 0 }
                ?.let { com.davnozdu.autoresponder.rules.SimUtil.subIdForSlot(context, it) }
                ?.takeIf { it >= 0 }
            ?: -1
        val keys = intent.extras?.keySet()?.joinToString(",") ?: "-"
        com.davnozdu.autoresponder.data.EventLog(context)
            .add("SMS вход: subId=$subId extras=[$keys] | ${com.davnozdu.autoresponder.rules.SimUtil.describe(context)}")
        // Команда с доверенного номера обрабатывается ДО истории и автоответа: она не
        // переписка с клиентом, ей нечего делать ни в контексте LLM, ни в лимитах.
        if (SmsCommands.handle(context, sender, body, subId)) return
        HistoryLogger.record(context, sender, "sms", "in", body)
        Responder.handle(context, sender, body, Kind.SMS, subId)
    }
}
