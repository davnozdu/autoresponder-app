package com.davnozdu.autoresponder.respond

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Сериализация обработки ОДНОГО адресата между двумя полосами очереди (main/msg).
 *
 * Полосы работают конкурентно, а анти-флуд [com.davnozdu.autoresponder.data.ReplyStore]
 * устроен как «проверить canReply → отправить → markReplied». Без блокировки событие из
 * SMS/звонка (main) и из RCS/мессенджера (msg) для одного и того же номера могли одновременно
 * пройти проверку и оба отправить ответ, превысив лимит. Мьютекс на ключ (обычно нормализованный
 * номер) закрывает это окно, при этом РАЗНЫЕ номера по-прежнему обрабатываются параллельно.
 */
object NumberLock {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private const val MAX_KEYS = 512

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        // Карта живёт всё время работы процесса, а ключей столько же, сколько уникальных
        // собеседников. Держим её ограниченной: свободные мьютексы можно выбросить —
        // при следующем обращении создастся новый.
        if (locks.size > MAX_KEYS) {
            locks.entries.removeAll { !it.value.isLocked && it.key != key }
        }
        return locks.getOrPut(key) { Mutex() }.withLock { block() }
    }
}
