package com.davnozdu.autoresponder.rules

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Поиск контакта по номеру с коротким кэшем.
 *
 * На одно входящее событие книга контактов опрашивалась трижды: SkipPolicy отдельно спрашивает
 * «звёздный?» и «есть в книге?», а DndPolicy — то же самое ещё раз. Всё это происходит в
 * CallScreeningService.onScreenCall, где система ждёт быстрого ответа. Один запрос отдаёт сразу
 * все три поля (known/starred/имя), результат живёт [TTL_MS] — этого хватает на обработку
 * события, а правки в книге контактов подхватываются почти сразу.
 */
object ContactUtil {

    private data class Lookup(val known: Boolean, val starred: Boolean, val name: String?)

    private const val TTL_MS = 60_000L
    private const val MAX_ENTRIES = 256
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Lookup>>()

    private fun lookup(context: Context, number: String?): Lookup {
        if (number.isNullOrBlank()) return Lookup(false, false, null)
        val key = number.trim()
        val now = System.currentTimeMillis()
        cache[key]?.let { (ts, v) -> if (now - ts < TTL_MS) return v }

        val result = try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(key))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID,
                        ContactsContract.PhoneLookup.STARRED,
                        ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst())
                    Lookup(true, c.getInt(1) == 1, c.getString(2)?.ifBlank { null })
                else null
            } ?: Lookup(false, false, null)
        } catch (e: Exception) {
            // Ошибку (нет разрешения, провайдер недоступен) НЕ кэшируем: разрешение может
            // появиться, и до истечения TTL мы бы считали всех незнакомцами.
            return Lookup(false, false, null)
        }

        if (cache.size > MAX_ENTRIES) cache.entries.removeAll { now - it.value.first > TTL_MS }
        cache[key] = now to result
        return result
    }

    /** Сбросить кэш (после импорта контактов и т.п.). */
    fun invalidate() = cache.clear()

    fun isKnownContact(context: Context, number: String?): Boolean = lookup(context, number).known

    /** Имя контакта по номеру или null. */
    fun nameFor(context: Context, number: String?): String? = lookup(context, number).name

    fun isStarred(context: Context, number: String?): Boolean = lookup(context, number).starred
}
