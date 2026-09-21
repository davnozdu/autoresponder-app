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

    /** Свежесть реестра — по тому источнику, что новее: память процесса или запись на флеше. */
    fun isFresh(now: Long, ramAt: Long, flashAt: Long, freshMs: Long): Boolean =
        now - maxOf(ramAt, flashAt) < freshMs

    /**
     * Пора ли записать отметку на флеш. Пишем только когда записанная уже устарела, то есть
     * не чаще раза в freshMs.
     *
     * Запись нужна ровно для одного случая: процесс перезапустился, сети нет, и без неё
     * приложение считало бы реестр негодным и дёргало CRM на каждое чужое сообщение.
     * Писать в момент, когда сети уже давно нет, смысла не имеет — к тому времени отметка
     * устарела сама. Поэтому пишем, пока знание свежее.
     */
    fun shouldPersist(now: Long, storedAt: Long, freshMs: Long): Boolean =
        now - storedAt >= freshMs

    /**
     * Обновить ли реестр из-за входящего звонка.
     *
     * Номера нет в реестре — это ещё не «не клиент»: реестр обновляется раз в 15 минут,
     * а клиента могли завести минуту назад. Ровно на этом карточка и не появилась в первый
     * раз. Звонок — событие редкое и заметное, одного условного запроса оно стоит, а ETag
     * делает неизменившийся ответ почти бесплатным.
     *
     * Номер уже известен — в сеть не идём вовсе. И шквал звонков с чужих номеров не должен
     * превращаться в шквал запросов, отсюда минимальный промежуток.
     */
    fun shouldRefreshForCall(online: Boolean, inRoster: Boolean,
                             sinceLastMs: Long, minGapMs: Long): Boolean =
        online && !inRoster && sinceLastMs >= minGapMs
}
