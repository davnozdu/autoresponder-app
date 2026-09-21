package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.crm.CrmLookup
import com.davnozdu.autoresponder.crm.CrmRecord
import com.davnozdu.autoresponder.notif.CallerCard
import org.junit.Assert.assertEquals
import org.junit.Test

class CallerCardTest {

    private fun order(number: String, device: String, label: String,
                      lastLabel: String? = null, lastAt: String? = null,
                      entity: String = "order", deadline: String? = null,
                      stage: String = "x") =
        CrmRecord(entity = entity, id = 1, number = number, device = device, stage = stage,
            label = label, lastLabel = lastLabel, lastAt = lastAt, deadline = deadline,
            price = null, canAsk = true)

    @Test fun `у клиента с заказом в шапке имя, под ней заказ и устройство`() {
        val card = CallerCard.render("Ян Новак", "+420800777708",
            CrmLookup(true, "cs", listOf(
                order("ZK-2026-0042", "MacBook Pro 14", "В работе", "Диагностика", "вчера"))))
        assertEquals("Ян Новак", card.title)
        assertEquals("ZK-2026-0042 · MacBook Pro 14", card.summary)
        assertEquals("В работе · Диагностика, вчера", card.details)
    }

    @Test fun `CRM не ответила — показываем хотя бы номер, а не пустоту`() {
        val card = CallerCard.render(null, "+420800777708", null)
        assertEquals("Звонит клиент", card.title)
        assertEquals("+420800777708", card.summary)
        assertEquals("", card.details)
    }

    @Test fun `у рекламации нет устройства — разделитель не висит, срок виден`() {
        val card = CallerCard.render("Ева Кратка", "+420777111222",
            CrmLookup(true, "cs", listOf(
                order("RK-2026-0007", "", "На рассмотрении", entity = "claim", deadline = "01.10.2026"))))
        assertEquals("RK-2026-0007", card.summary)
        assertEquals("На рассмотрении · срок до 01.10.2026", card.details)
    }

    @Test fun `две открытые записи — обе видны, каждая своей строкой`() {
        val card = CallerCard.render("Ян Новак", "+420800777708",
            CrmLookup(true, "cs", listOf(
                order("ZK-2026-0042", "MacBook Pro 14", "В работе"),
                order("ZK-2026-0051", "iPhone 15", "Готов к выдаче"))))
        assertEquals("ZK-2026-0042 · MacBook Pro 14", card.summary)
        assertEquals("В работе\nZK-2026-0051 · iPhone 15 — Готов к выдаче", card.details)
    }

    @Test fun `мастеру этап по-русски, хотя клиенту CRM прислала чешский`() {
        val card = CallerCard.render("Jan Novák", "+420800777708",
            CrmLookup(true, "cs", listOf(
                order("ZK-2026-0042", "MacBook Pro 14", "V práci", stage = "in_progress"))))
        assertEquals("В работе", card.details)
    }

    @Test fun `этап рекламации тоже по-русски — коды у заказа и рекламации разные`() {
        val card = CallerCard.render("Eva Krátká", "+420777111222",
            CrmLookup(true, "cs", listOf(
                order("RK-2026-0007", "", "V posouzení", entity = "claim", stage = "review"))))
        assertEquals("В рассмотрении", card.details)
    }

    @Test fun `незнакомый код этапа — показываем что прислала CRM, а не пустоту`() {
        val card = CallerCard.render("Jan Novák", "+420800777708",
            CrmLookup(true, "cs", listOf(
                order("ZK-2026-0099", "iPhone 15", "Nový stav", stage = "stage_from_future"))))
        assertEquals("Nový stav", card.details)
    }
}
