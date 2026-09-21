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
}
