package com.davnozdu.autoresponder.respond

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.davnozdu.autoresponder.sms.SmsSentReceiver

/** Отправка ответного SMS многосегментно + отслеживание доставки (повтор при сбое). */
object SmsSender {

    fun send(context: Context, number: String, text: String, subId: Int = -1, attempt: Int = 0): Int {
        return try {
            val sm = smsManager(context, subId)
            val parts = sm.divideMessage(text)
            // Статус запрашиваем только для ОДНОСЕГМЕНТНЫХ сообщений: повтор пересылает текст
            // целиком, и для многосегментного это продублировало бы уже доставленные части.
            val sent = ArrayList<PendingIntent?>()
            for (i in parts.indices) {
                sent.add(if (i == 0 && parts.size == 1) sentPi(context, number, text, subId, attempt) else null)
            }
            sm.sendMultipartTextMessage(number, null, parts, sent, null)
            parts.size
        } catch (e: Exception) {
            // Частая причина — устаревший subId (карту вынули, переключили eSIM):
            // сбрасываем кэш, чтобы следующая попытка увидела реальный список карт.
            com.davnozdu.autoresponder.rules.SimUtil.invalidate()
            -1
        }
    }

    private fun sentPi(context: Context, number: String, text: String, subId: Int, attempt: Int): PendingIntent {
        val i = Intent(context, SmsSentReceiver::class.java).apply {
            action = "com.davnozdu.autoresponder.SMS_SENT"
            putExtra("number", number); putExtra("text", text)
            putExtra("subId", subId); putExtra("attempt", attempt)
        }
        val rc = (number + text + attempt).hashCode()
        return PendingIntent.getBroadcast(context, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun segmentCount(context: Context, text: String, subId: Int = -1): Int =
        try { smsManager(context, subId).divideMessage(text).size } catch (e: Exception) { -1 }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subId: Int): SmsManager {
        return if (subId >= 0) {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            else SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
        }
    }
}
