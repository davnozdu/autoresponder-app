package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.crm.CrmSyncPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmSyncPolicyTest {

    private fun should(online: Boolean = true, working: Boolean = true, cold: Boolean = false,
                       since: Long = 20 * 60_000L) =
        CrmSyncPolicy.shouldSync(crmReady = true, online = online, working = working,
            coldCache = cold, sinceLastMs = since, intervalMs = 15 * 60_000L)

    @Test fun `нет сети — не ходим и не пытаемся`() {
        assertFalse(should(online = false))
        assertFalse(should(online = false, cold = true))   // даже с пустым кешем
    }

    @Test fun `ночью не ходим — статусы всё равно не меняются`() {
        assertFalse(should(working = false))
    }

    @Test fun `пустой кеш после запуска — идём даже вне рабочих часов`() {
        assertTrue(should(working = false, cold = true, since = 0))
    }

    @Test fun `интервал не вышел — не долбим`() {
        assertFalse(should(since = 5 * 60_000L))
    }

    @Test fun `рабочее время, сеть есть, интервал вышел — идём`() {
        assertTrue(should())
    }

    @Test fun `CRM не настроена — не ходим никогда`() {
        assertFalse(CrmSyncPolicy.shouldSync(crmReady = false, online = true, working = true,
            coldCache = true, sinceLastMs = Long.MAX_VALUE, intervalMs = 0))
    }

    private val hour = 60 * 60_000L

    @Test fun `на флеш пишем, только когда записанная отметка уже устарела`() {
        assertTrue(CrmSyncPolicy.shouldPersist(now = 10 * hour, storedAt = 9 * hour, freshMs = hour))
        assertFalse(CrmSyncPolicy.shouldPersist(now = 10 * hour, storedAt = 9 * hour + 1, freshMs = hour))
        assertTrue(CrmSyncPolicy.shouldPersist(now = hour, storedAt = 0, freshMs = hour))
    }

    @Test fun `свежесть считается по тому, что новее — память или флеш`() {
        // Процесс только поднялся: в памяти пусто, а на флеше отметка получаса назад.
        assertTrue(CrmSyncPolicy.isFresh(now = 10 * hour, ramAt = 0,
            flashAt = 10 * hour - hour / 2, freshMs = hour))
        // Без сети вторые сутки — верить нечему, спрашиваем CRM на всякий случай.
        assertFalse(CrmSyncPolicy.isFresh(now = 48 * hour, ramAt = 0,
            flashAt = 10 * hour, freshMs = hour))
        // Обычная работа: в памяти отметка свежее записанной.
        assertTrue(CrmSyncPolicy.isFresh(now = 10 * hour, ramAt = 10 * hour - 60_000,
            flashAt = 0, freshMs = hour))
    }

    @Test fun `звонок с номера вне реестра обновляет реестр — клиента могли завести только что`() {
        assertTrue(CrmSyncPolicy.shouldRefreshForCall(
            online = true, inRoster = false, sinceLastMs = 5 * 60_000L, minGapMs = 60_000L))
    }

    @Test fun `номер уже в реестре — в сеть не идём, отвечаем из памяти`() {
        assertFalse(CrmSyncPolicy.shouldRefreshForCall(
            online = true, inRoster = true, sinceLastMs = 60 * 60_000L, minGapMs = 60_000L))
    }

    @Test fun `без сети звонок реестр не обновляет`() {
        assertFalse(CrmSyncPolicy.shouldRefreshForCall(
            online = false, inRoster = false, sinceLastMs = 60 * 60_000L, minGapMs = 60_000L))
    }

    @Test fun `шквал звонков с чужих номеров не превращается в шквал запросов`() {
        assertFalse(CrmSyncPolicy.shouldRefreshForCall(
            online = true, inRoster = false, sinceLastMs = 10_000L, minGapMs = 60_000L))
    }
}
