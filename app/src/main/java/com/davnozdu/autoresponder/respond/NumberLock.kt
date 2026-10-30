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

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        // Чистку карты убрали: она удаляла «незахваченный» мьютекс, у которого уже был
        // ожидающий (isLocked=false в окне между unlock и захватом), и следующий вызов получал
        // НОВЫЙ мьютекс на тот же ключ — двое входили в критическую секцию, которую этот класс
        // и должен был закрыть. Ключей столько же, сколько собеседников (сотни), на память это
        // не влияет.
        return locks.getOrPut(key) { Mutex() }.withLock { block() }
    }
}
