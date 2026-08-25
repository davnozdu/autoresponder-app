package com.davnozdu.autoresponder.store

import android.content.Context
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.PhoneMask

/** Правила сохранения истории. */
object HistoryLogger {

    /** Реальный телефонный номер (не короткий, не сервис/буквенный). */
    private fun isRealNumber(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        if (PhoneMask.isAlphanumericSender(raw)) return false
        val digits = raw.count { it.isDigit() }
        return digits >= 8
    }

    /**
     * channel: sms|rcs|call → нужен реальный номер; whatsapp|telegram → по имени (identity=sender).
     * direction: in|out
     */
    fun record(context: Context, identity: String?, channel: String, direction: String, body: String, auto: Boolean = false) {
        if (identity.isNullOrBlank()) return
        val app = context.applicationContext
        when (channel) {
            "sms", "rcs", "call" -> {
                if (!isRealNumber(identity)) return
                val num = PhoneMask.normalize(identity) ?: identity
                val name = ContactUtil.nameFor(app, num)
                HistoryDb.get(app).insert(num, name, channel, direction, body, auto = auto)
            }
            else -> { // whatsapp/telegram: идентичность = имя отправителя
                HistoryDb.get(app).insert(identity.trim(), identity.trim(), channel, direction, body, auto = auto)
            }
        }
    }
}
