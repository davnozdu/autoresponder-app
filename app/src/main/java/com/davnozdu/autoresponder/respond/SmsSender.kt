package com.davnozdu.autoresponder.respond

import android.content.Context
import android.telephony.SmsManager

/** Отправка ответного SMS многосегментно (никогда не конвертируется в MMS). */
object SmsSender {

    /** @return число сегментов, либо -1 при ошибке. */
    fun send(context: Context, number: String, text: String, subId: Int = -1): Int {
        return try {
            val sm = smsManager(context, subId)
            val parts = sm.divideMessage(text)
            sm.sendMultipartTextMessage(number, null, parts, null, null)
            parts.size
        } catch (e: Exception) {
            -1
        }
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
