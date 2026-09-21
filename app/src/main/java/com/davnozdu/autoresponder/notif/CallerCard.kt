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
            stage(first).ifBlank { null },
            first.lastLabel?.let { l -> first.lastAt?.let { "$l, $it" } ?: l },
            first.deadline?.let { "срок до $it" }
        ).joinToString(" · ")
        val rest = records.drop(1).map { "${line(it)} — ${stage(it)}" }
        return Card(title, line(first), (listOf(head) + rest).filter { it.isNotBlank() }.joinToString("\n"))
    }

    /**
     * Этап — мастеру по-русски. CRM присылает `label` уже на языке КЛИЕНТА (он и уходит
     * клиенту в SMS), а в карточке на телефоне мастера чешская подпись бесполезна.
     * Код этапа языка не имеет, поэтому переводим по нему. Таблица осознанно дублирует
     * portal_status_label() из CRM: карточка должна собираться из кеша и без сети.
     * Незнакомый код — отдаём как есть, лучше чужой язык, чем пустота.
     */
    private val RU = mapOf(
        "order" to mapOf(
            "new" to "Принят", "diagnostics" to "Диагностика",
            "waiting_parts" to "Ожидание запчастей", "in_progress" to "В работе",
            "ready" to "Готов к выдаче", "issued" to "Выдан", "declined" to "Отказ от ремонта"),
        "claim" to mapOf(
            "new" to "Подана", "accepted" to "Принята", "review" to "В рассмотрении",
            "decided" to "Решение принято", "settled" to "Рассмотрена"))

    private fun stage(r: CrmRecord) = RU[r.entity]?.get(r.stage) ?: r.label

    /** У рекламации устройства нет — разделитель не должен висеть. */
    private fun line(r: CrmRecord) =
        listOfNotNull(r.number.ifBlank { null }, r.device.ifBlank { null }).joinToString(" · ")
}
