package com.davnozdu.autoresponder.data

import android.content.Context

/**
 * Персистентный учёт последних авто-ответов на номер — защита от петель.
 */
class ReplyStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("autoresp_replies", Context.MODE_PRIVATE)

    /** true, если на этот номер уже отвечали в пределах cooldown-окна */
    fun isOnCooldown(number: String, cooldownHours: Int): Boolean {
        val last = sp.getLong(key(number), 0L)
        if (last == 0L) return false
        val elapsed = System.currentTimeMillis() - last
        return elapsed < cooldownHours * 3600_000L
    }

    fun markReplied(number: String) {
        sp.edit().putLong(key(number), System.currentTimeMillis()).apply()
    }

    private fun key(number: String) = "last_" + number.filter { it.isDigit() || it == '+' }
}
