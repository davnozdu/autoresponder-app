package com.davnozdu.autoresponder

import org.junit.Test
import org.junit.Assert.*
import com.davnozdu.autoresponder.respond.ConversationContext
import com.davnozdu.autoresponder.respond.Handoff
import com.davnozdu.autoresponder.store.HistItem

class ReliabilityTest {
    @Test fun `delivery needs a final successful modem report`() {
        val d=com.davnozdu.autoresponder.sms.DeliveryResult
        assertNull(d.confirmed(-1,null)); assertNull(d.confirmed(-1,32))
        assertEquals(-1,d.confirmed(-1,0)); assertEquals(64,d.confirmed(-1,64))
    }
    @Test fun `human messages remain human and long recent turns remain whole`() {
        val text="Договорились о ремонте. ".repeat(40)
        val result=ConversationContext.render(listOf(HistItem(1,"n",null,"sms","out",text,1,false)))
        assertTrue(result.contains("Master (human owner)"))
        assertTrue(result.contains(text))
    }
    @Test fun `budget retains complete newest turns and drops older ones`() {
        val items=(1..5).map { HistItem(it.toLong(),"n",null,"sms","in","$it:"+"x".repeat(100),it.toLong()) }
        val result=ConversationContext.render(items,250)
        assertTrue(result.contains("5:")); assertTrue(result.contains("4:")); assertFalse(result.contains("3:"))
        assertTrue(result.length<=250)
    }
    @Test fun `timed pause does not release old queued replies`() {
        assertTrue(Handoff.blocked(2000,1000,1500,1900))
        assertTrue(Handoff.blocked(2000,1000,1500,3000))
        assertFalse(Handoff.blocked(2000,1000,2500,3000))
    }
    @Test fun `manual resume cancels pre-resume work`() {
        assertTrue(Handoff.blocked(0,3000,2500,4000))
        assertFalse(Handoff.blocked(0,3000,3500,4000))
        assertTrue(Handoff.blocked(Long.MAX_VALUE,1000,5000,6000))
    }
}
