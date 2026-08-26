package com.davnozdu.autoresponder.data

import android.content.Context

/** Режим ответа по текущему счётчику: обычный AI-ответ, системное предупреждение, тишина. */
enum class ReplyMode { NORMAL, WARN, SILENT }

/**
 * Анти-флуд на номер: не более [maxReplies] обычных авто-ответов, затем ОДНО системное
 * предупреждение (№ maxReplies+1) и таймаут [timeoutHours]. Окно скользит от последнего ответа:
 * после лимита номер молчит, пока не пройдёт таймаут, после чего счётчик обнуляется.
 */
class ReplyStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("autoresp_replies", Context.MODE_PRIVATE)

    /** Эффективный счётчик с учётом сброса по таймауту. */
    private fun effectiveCount(number: String, timeoutHours: Int): Int {
        val k = key(number)
        val count = sp.getInt(k + "_c", 0)
        val last = sp.getLong(k + "_t", 0L)
        if (count == 0 || last == 0L) return 0
        val windowMs = timeoutHours * 3600_000L
        return if (System.currentTimeMillis() - last >= windowMs) 0 else count
    }

    /** Можно ли ответить сейчас (обычный ответ или предупреждение). */
    fun canReply(number: String, maxReplies: Int, timeoutHours: Int): Boolean =
        effectiveCount(number, timeoutHours) < maxReplies

    /**
     * Режим для очередного ответа:
     *  - NORMAL, пока отправлено < maxReplies обычных;
     *  - WARN — ровно один раз на счётчике == maxReplies (если [warnEnabled]);
     *  - SILENT — дальше (или сразу, если warn выключен).
     */
    fun replyMode(number: String, maxReplies: Int, timeoutHours: Int, warnEnabled: Boolean): ReplyMode {
        val c = effectiveCount(number, timeoutHours)
        return when {
            c < maxReplies -> ReplyMode.NORMAL
            warnEnabled && c == maxReplies -> ReplyMode.WARN
            else -> ReplyMode.SILENT
        }
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
