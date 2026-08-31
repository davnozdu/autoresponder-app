package com.davnozdu.autoresponder.store

import android.content.Context
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.PhoneMask

/**
 * Склейка веток истории по ЧЕЛОВЕКУ, а не по каналу.
 *
 * История хранится по `events.number`, и ключ у разных каналов свой: звонки/SMS/RCS пишутся по
 * нормализованному номеру, WhatsApp и Telegram — по имени отправителя из уведомления (номера они
 * не передают). Из-за этого один и тот же клиент, написавший в WhatsApp, а потом приславший SMS,
 * выглядел как двое разных, и LLM не видела предыдущего разговора.
 *
 * Здесь ключ разворачивается в набор ключей одного человека: имя ↔ все номера контакта из
 * телефонной книги. Дальше набор сопоставляется с реальными ключами БД — сравнением строк без
 * учёта регистра и номеров по цифрам, потому что формат записи у источников разный
 * («+420704419226» из SMS и «+420 704 419 226» из WhatsApp — один человек).
 */
object PersonThreads {

    private const val TTL_MS = 60_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()

    fun keysFor(context: Context, key: String?): List<String> {
        val seed = key?.trim().orEmpty()
        if (seed.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        cache[seed]?.let { (ts, v) -> if (now - ts < TTL_MS) return v }

        // 1) Идентичности человека: сам ключ + имя контакта + все его номера.
        val wanted = LinkedHashSet<String>()
        wanted.add(seed)
        if (PhoneMask.looksLikeNumber(seed)) {
            ContactUtil.nameFor(context, seed)?.let { wanted.add(it) }
            wanted.addAll(ContactUtil.numbersForNumber(context, seed))
        } else {
            wanted.addAll(ContactUtil.numbersForName(context, seed))
        }

        // 2) Какие из них реально есть в БД (веток немного — сотни, сравниваем в Kotlin:
        //    в SQL «тот же номер в другом формате» не выразить).
        val out = LinkedHashSet<String>()
        out.add(seed)
        try {
            for (k in HistoryDb.get(context).distinctKeys()) {
                if (wanted.any { w -> k.equals(w, ignoreCase = true) || PhoneMask.sameNumber(k, w) })
                    out.add(k)
            }
        } catch (_: Exception) { /* БД недоступна — работаем по одному ключу */ }

        val res = out.toList()
        if (cache.size > 128) cache.entries.removeAll { now - it.value.first > TTL_MS }
        cache[seed] = now to res
        return res
    }

    /** Сбросить кэш (после импорта/очистки истории). */
    fun invalidate() = cache.clear()
}
