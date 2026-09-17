package com.davnozdu.autoresponder.respond

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.davnozdu.autoresponder.sms.SmsSentReceiver

/** Отправка ответного SMS многосегментно + отслеживание доставки (повтор при сбое). */
object SmsSender {

    fun send(context: Context, number: String, text: String, subId: Int = -1, attempt: Int = 0,
             jobId: Long = 0, historyChannel: String = "", limitKey: String = "", timeoutHours: Int = 1): Int {
        var outgoing: String? = null
        return try {
            val sm = smsManager(context, subId)
            val parts = sm.divideMessage(text)
            if (!EventQueue.beforeSend(context, jobId)) return -1
            val id = Outgoing.create(context, number, "sms", text, parts.size, jobId, historyChannel, limitKey, timeoutHours)
            outgoing = id
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent>()
            parts.indices.forEach { n ->
                sent.add(statusPi(context,id,n,false))
                delivered.add(statusPi(context,id,n,true))
            }
            sm.sendMultipartTextMessage(number, null, parts, sent, delivered)
            parts.size
        } catch (e: Exception) {
            outgoing?.let { Outgoing.failed(context,it,"${e.javaClass.simpleName}: ${e.message}") }
            com.davnozdu.autoresponder.rules.SimUtil.invalidate()
            -1
        }
    }

    private fun statusPi(context: Context, id: String, part: Int, delivery: Boolean): PendingIntent {
        val i = Intent(context, SmsSentReceiver::class.java).apply {
            action = if(delivery) "com.davnozdu.autoresponder.SMS_DELIVERED" else "com.davnozdu.autoresponder.SMS_SENT"
            data = android.net.Uri.parse("autoresp://sms/$id/$part/${if(delivery) "delivery" else "sent"}")
            putExtra("outgoing",id); putExtra("part",part); putExtra("delivery",delivery)
        }
        return PendingIntent.getBroadcast(context,0,i,PendingIntent.FLAG_UPDATE_CURRENT or if (delivery) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
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
