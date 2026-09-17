package com.davnozdu.autoresponder.sms

/** TP-Status 0..31 = completed, 32..63 = still pending, 64+ = permanent failure. */
object DeliveryResult {
    fun confirmed(result: Int, status: Int?): Int? = when {
        result != -1 -> result
        status == null || status < 0 || status in 32..63 -> null
        status < 32 -> -1
        else -> status
    }
}
