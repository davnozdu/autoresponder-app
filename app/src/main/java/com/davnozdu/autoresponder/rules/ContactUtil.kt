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

    private data class Lookup(val known: Boolean, val starred: Boolean, val name: String?,
                              val contactId: Long = 0L)

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
                arrayOf(ContactsContract.PhoneLookup.CONTACT_ID,
                        ContactsContract.PhoneLookup.STARRED,
                        ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst())
                    Lookup(true, c.getInt(1) == 1, c.getString(2)?.ifBlank { null }, c.getLong(0))
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
    fun invalidate() { cache.clear(); nameCache.clear() }


    // --- Поиск по ИМЕНИ ---
    //
    // Мессенджеры не передают номер: WhatsApp и Telegram подставляют в уведомление имя из
    // телефонной книги. Чтобы переключатели «не отвечать звёздным / всем контактам» работали
    // и для них, контакт ищем по отображаемому имени.
    //
    // CONTENT_FILTER_URI отдаёт совпадения по началу слов (надмножество), точное сравнение
    // делаем в Kotlin: SQLite-коллация NOCASE не сворачивает регистр кириллицы.

    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Lookup>>()

    private fun lookupByName(context: Context, name: String?): Lookup {
        val key = name?.trim() ?: return Lookup(false, false, null)
        if (key.isEmpty()) return Lookup(false, false, null)
        val now = System.currentTimeMillis()
        nameCache[key]?.let { (ts, v) -> if (now - ts < TTL_MS) return v }

        val result = try {
            val uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(key))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.STARRED,
                        ContactsContract.Contacts._ID),
                null, null, null
            )?.use { c ->
                var found: Lookup? = null
                while (c.moveToNext()) {
                    val dn = c.getString(0)?.trim() ?: continue
                    if (!dn.equals(key, ignoreCase = true)) continue
                    val starred = c.getInt(1) == 1
                    // Одно имя может быть у нескольких контактов — звёздный среди них главнее.
                    if (starred) { found = Lookup(true, true, dn, c.getLong(2)); break }
                    found = Lookup(true, false, dn, c.getLong(2))
                }
                found
            } ?: Lookup(false, false, null)
        } catch (e: Exception) {
            return Lookup(false, false, null)   // ошибку не кэшируем (см. lookup выше)
        }

        if (nameCache.size > MAX_ENTRIES) nameCache.entries.removeAll { now - it.value.first > TTL_MS }
        nameCache[key] = now to result
        return result
    }

    /** Контакт с таким отображаемым именем есть в книге. */
    fun isKnownName(context: Context, name: String?): Boolean = lookupByName(context, name).known

    /** Контакт с таким отображаемым именем помечен звёздочкой. */
    fun isStarredName(context: Context, name: String?): Boolean = lookupByName(context, name).starred

    /**
     * ВСЕ номера контакта, которому принадлежит [contactId].
     *
     * Нужно, чтобы склеить историю одного человека: у контакта бывает несколько номеров,
     * и SMS с рабочего и звонок с мобильного — это одна переписка.
     */
    private fun numbersOf(context: Context, contactId: Long): List<String> {
        if (contactId <= 0L) return emptyList()
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()), null
            )?.use { c ->
                val out = LinkedHashSet<String>()
                while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                out.toList()
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    /** Все номера контакта, найденного по одному из его номеров. */
    fun numbersForNumber(context: Context, number: String?): List<String> =
        numbersOf(context, lookup(context, number).contactId)

    /** Все номера контакта, найденного по отображаемому имени. */
    fun numbersForName(context: Context, name: String?): List<String> =
        numbersOf(context, lookupByName(context, name).contactId)

    /**
     * Имена звёздных (избранных) контактов — для импорта в список избранного мессенджеров.
     * Именно эти строки WhatsApp и Telegram показывают в уведомлении.
     */
    fun starredNames(context: Context): List<String> = try {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.STARRED} = 1", null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )?.use { c ->
            val out = LinkedHashSet<String>()
            while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            out.toList()
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun isKnownContact(context: Context, number: String?): Boolean = lookup(context, number).known

    /** Имя контакта по номеру или null. */
    fun nameFor(context: Context, number: String?): String? = lookup(context, number).name

    fun isStarred(context: Context, number: String?): Boolean = lookup(context, number).starred
}
