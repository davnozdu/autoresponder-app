package com.davnozdu.autoresponder.crm

/**
 * Ходить ли сейчас в CRM. Android здесь не нужен — решают только аргументы,
 * поэтому правило целиком проверяется юнит-тестом.
 *
 * Смысл расписания: статусы меняются, пока мастер работает, а робот отвечает
 * клиентам ночью. Значит опрашивать надо днём, чтобы к ночи данные были свежими,
 * а ночью молчать. Исключение — пустой кеш после запуска процесса: без одного
 * похода работать нечем, и он стоит одного запроса.
 */
object CrmSyncPolicy {
    fun shouldSync(crmReady: Boolean, online: Boolean, working: Boolean,
                   coldCache: Boolean, sinceLastMs: Long, intervalMs: Long): Boolean {
        if (!crmReady) return false
        if (!online) return false          // нет сети — не дёргаем радио впустую
        if (coldCache) return true
        if (!working) return false
        return sinceLastMs >= intervalMs
    }
}
