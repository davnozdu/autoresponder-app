package com.davnozdu.autoresponder.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.store.BlackEntry
import com.davnozdu.autoresponder.store.HistoryDb

/*
 * Выбор срока блокировки — общий для экрана чёрного списка и для истории общения.
 * Заводить запись «на три часа» логично там, где человек только что достал, а не
 * возвращаясь на отдельный экран и вводя номер руками.
 */

/** Срок блокировки: либо длительность от «сейчас», либо конкретная дата. 0/0 — навсегда. */
internal data class Term(val delta: Long = 0L, val abs: Long = 0L) {
    /** Момент окончания для записи в БД: 0 — навсегда. */
    fun until(): Long = when {
        abs > 0L -> abs
        delta > 0L -> System.currentTimeMillis() + delta
        else -> 0L
    }
}

private const val HOUR = 3_600_000L
private val PRESETS = listOf(
    "Навсегда" to Term(),
    "1 час" to Term(delta = HOUR),
    "3 часа" to Term(delta = 3 * HOUR),
    "1 день" to Term(delta = 24 * HOUR),
)

internal fun fmtDate(ts: Long): String =
    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

private fun fmtLeft(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> "осталось $min мин"
        min < 24 * 60 -> "осталось ${min / 60} ч"
        else -> "осталось ${min / (24 * 60)} дн"
    }
}

/** Подпись срока в списке. */
internal fun fmtUntil(until: Long): String {
    if (until <= 0L) return "Блокировка: навсегда"
    val left = until - System.currentTimeMillis()
    if (left <= 0L) return "Срок истёк — запись не действует"
    return "До ${fmtDate(until)} (${fmtLeft(left)})"
}

/** Календарь системы; блокируем до конца выбранного дня (23:59). */
internal fun pickDate(ctx: Context, initial: Long, onSet: (Long) -> Unit) {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = maxOf(initial, System.currentTimeMillis())
    }
    val dlg = android.app.DatePickerDialog(ctx, { _, y, mo, d ->
        val c = java.util.Calendar.getInstance().apply {
            set(y, mo, d, 23, 59, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        onSet(c.timeInMillis)
    }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
       cal.get(java.util.Calendar.DAY_OF_MONTH))
    dlg.datePicker.minDate = System.currentTimeMillis()
    dlg.show()
}

/** Ряд пресетов + «Дата…». [until] — текущее значение записи (0 — навсегда). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TermChips(until: Long, onPick: (Term) -> Unit) {
    val ctx = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PRESETS.forEach { (label, term) ->
            // Пресеты не «залипают»: сохранённый срок — это дата, а не длительность.
            // Отмечаем только «Навсегда», остальное видно по подписи с датой.
            FilterChip(selected = term.delta == 0L && until == 0L,
                onClick = { onPick(term) }, label = { Text(label) })
        }
        FilterChip(selected = until > 0L, onClick = { pickDate(ctx, until) { onPick(Term(abs = it)) } },
            label = { Text(if (until > 0L) fmtDate(until) else "Дата…") })
    }
}

/**
 * «В чёрный список» одним касанием: срок выбирается тут же.
 *
 * Существующая запись не дублируется — у неё меняется срок: иначе после третьего
 * нажатия в списке было бы три записи на один номер и непонятно, какая действует.
 */
@Composable
internal fun BlockDialog(identity: String, name: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    var term by remember { mutableStateOf(Term()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("В чёрный список: ${name ?: identity}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmtUntil(term.until()), style = MaterialTheme.typography.bodySmall)
                TermChips(term.until()) { term = it }
                Text("Звонки будут отклоняться, сообщения — без ответа. Работает и в рабочее время.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val db = HistoryDb.get(ctx)
                val id = com.davnozdu.autoresponder.rules.PhoneMask.canonical(identity)
                val until = term.until()
                val exists = db.blacklistAll().firstOrNull {
                    com.davnozdu.autoresponder.rules.NameMatch.matches(id, it.identity)
                }
                if (exists != null) db.blacklistUpsert(exists.copy(untilTs = until))
                else db.blacklistUpsert(BlackEntry(0, id, name, false, null, untilTs = until))
                onDone(fmtUntil(until))
            }) { Text("Заблокировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
