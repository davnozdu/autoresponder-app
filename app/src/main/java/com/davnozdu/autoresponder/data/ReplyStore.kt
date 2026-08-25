package com.davnozdu.autoresponder.data

import android.content.Context

/**
 * Анти-флуд на номер: не более [maxReplies] авто-ответов, затем таймаут [timeoutHours].
 * Окно скользит от последнего ответа: после лимита номер молчит, пока не пройдёт таймаут,
 * после чего счётчик обнуляется.
 */
class ReplyStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("autoresp_replies", Context.MODE_PRIVATE)

    /** Можно ли ответить сейчас. */
    fun canReply(number: String, maxReplies: Int, timeoutHours: Int): Boolean {
        val k = key(number)
        val count = sp.getInt(k + "_c", 0)
        val last = sp.getLong(k + "_t", 0L)
        if (count == 0 || last == 0L) return true
        val windowMs = timeoutHours * 3600_000L
        if (System.currentTimeMillis() - last >= windowMs) return true // таймаут прошёл → сброс
        return count < maxReplies
    }

    /** Зафиксировать отправленный ответ (учитывает сброс по таймауту). */
    fun markReplied(number: String, timeoutHours: Int) {
        val k = key(number)
        val last = sp.getLong(k + "_t", 0L)
        val windowMs = timeoutHours * 3600_000L
        val prev = if (last == 0L || System.currentTimeMillis() - last >= windowMs) 0
                   else sp.getInt(k + "_c", 0)
        sp.edit()
            .putInt(k + "_c", prev + 1)
            .putLong(k + "_t", System.currentTimeMillis())
            .apply()
    }

    /** Текущий счётчик (для лога). */
    fun count(number: String): Int = sp.getInt(key(number) + "_c", 0)

    // Ключ: телефоны нормализуются (пробелы/дефисы прочь), имена мессенджеров сохраняются.
    private fun key(id: String) =
        "n_" + id.lowercase().filter { it.isLetterOrDigit() || it == '+' || it == ':' }
}
