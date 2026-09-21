package com.davnozdu.autoresponder.notif

import com.davnozdu.autoresponder.crm.CrmLookup
import com.davnozdu.autoresponder.crm.CrmRecord

/**
 * Что показать о звонящем: шапка, строка под ней и развёрнутые подробности.
 * Чистая сборка текста — без Android, чтобы проверялась юнит-тестом.
 */
object CallerCard {
    data class Card(val title: String, val summary: String, val details: String)

    fun render(name: String?, number: String, lookup: CrmLookup?): Card {
        val title = name?.takeIf { it.isNotBlank() } ?: "Звонит клиент"
        val records = lookup?.records.orEmpty()
        // CRM не ответила или открытых записей нет — показываем хотя бы номер.
        if (records.isEmpty()) return Card(title, number, "")
        val first = records.first()
        val head = listOfNotNull(
            first.label.ifBlank { null },
            first.lastLabel?.let { l -> first.lastAt?.let { "$l, $it" } ?: l },
            first.deadline?.let { "срок до $it" }
        ).joinToString(" · ")
        val rest = records.drop(1).map { "${line(it)} — ${it.label}" }
        return Card(title, line(first), (listOf(head) + rest).filter { it.isNotBlank() }.joinToString("\n"))
    }

    /** У рекламации устройства нет — разделитель не должен висеть. */
    private fun line(r: CrmRecord) =
        listOfNotNull(r.number.ifBlank { null }, r.device.ifBlank { null }).joinToString(" · ")
}
