package com.davnozdu.autoresponder.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.store.BlackEntry
import com.davnozdu.autoresponder.store.HistoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlacklistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { BlacklistScreen() } }
    }
}

private const val DEFAULT_CALL_PROMPT =
    "Сейчас мы не можем ответить на звонок. Напишите нам сообщение, и мы свяжемся с вами в ближайшее время."

private const val DEFAULT_BL_PROMPT =
    "Это нежелательный контакт. Учитывай текущий режим: если сейчас нерабочее время (Не беспокоить включён) — " +
    "коротко и вежливо сообщи, что сейчас нерабочее время. В рабочее время отвечай сухо, коротко и спокойно, " +
    "как психолог: не вступай в споры, не критикуй, ничего не обещай и ничего не разбирай. Просто дай понять, " +
    "что мы услышали и позиция человека понятна (\"Мы вас услышали\", \"Ваша заявка принята\", " +
    "\"Ваше беспокойство нам понятно\"). Если человек на эмоциях — мягко успокой, но без фанатизма. " +
    "Строго в рамках лимита символов."

/** Срок блокировки: либо длительность от «сейчас», либо конкретная дата. 0/0 — навсегда. */
private data class Term(val delta: Long = 0L, val abs: Long = 0L) {
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

private fun fmtDate(ts: Long): String =
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
private fun fmtUntil(until: Long): String {
    if (until <= 0L) return "Блокировка: навсегда"
    val left = until - System.currentTimeMillis()
    if (left <= 0L) return "Срок истёк — запись не действует"
    return "До ${fmtDate(until)} (${fmtLeft(left)})"
}

/** Календарь системы; блокируем до конца выбранного дня (23:59). */
private fun pickDate(ctx: Context, initial: Long, onSet: (Long) -> Unit) {
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
private fun TermChips(until: Long, onPick: (Term) -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BlacklistScreen() {
    val ctx = LocalContext.current
    val db = remember { HistoryDb.get(ctx) }
    var items by remember { mutableStateOf(listOf<BlackEntry>()) }
    var newId by remember { mutableStateOf("") }
    // Срок для НОВЫХ записей (как через «+», так и из контактов). Term() — навсегда.
    // Храним длительность, а не момент: «1 час» отсчитывается от добавления, а не от выбора чипа.
    var newTerm by remember { mutableStateOf(Term()) }
    var editing by remember { mutableStateOf<BlackEntry?>(null) }
    var editingCall by remember { mutableStateOf<BlackEntry?>(null) }

    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch { items = withContext(Dispatchers.IO) { db.blacklistAll() } }
    }
    LaunchedEffect(Unit) { reload() }

    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (uri != null) {
            ctx.contentResolver.query(uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    db.blacklistUpsert(BlackEntry(0,
                        com.davnozdu.autoresponder.rules.PhoneMask.canonical(it.getString(0)),
                        it.getString(1), false, null, untilTs = newTerm.until()))
                    reload()
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Чёрный список") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            Text("Кому не хотим отвечать. Галочка «через LLM» — вместо тишины отвечать своим промптом.",
                style = MaterialTheme.typography.bodySmall)
            Text("Чёрный список не смотрит на рабочее время: он срабатывает и когда открыто. "
                + "Маску стран и «Избранных» тоже обходит — во всех каналах одинаково. "
                + "Но подчиняется главному тумблеру, паузе и лимиту ответов.",
                style = MaterialTheme.typography.bodySmall)
            Text("Запись — номер, имя из телефонной книги или маска: * — любой текст, "
                + "? — один символ («+420*», «*спам*»).",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newId, { newId = it }, label = { Text("Номер, имя или маска") },
                    modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = {
                    if (newId.isNotBlank()) {
                        db.blacklistUpsert(BlackEntry(0,
                            com.davnozdu.autoresponder.rules.PhoneMask.canonical(newId),
                            null, false, null, untilTs = newTerm.until()))
                        newId = ""; reload()
                    }
                }) { Text("+") }
            }
            val canon = com.davnozdu.autoresponder.rules.PhoneMask.canonical(newId)
            if (canon != newId.trim()) Text("Будет сохранено: $canon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Text("Насколько блокировать (для новой записи): ${fmtUntil(newTerm.until())}",
                style = MaterialTheme.typography.bodySmall)
            TermChips(newTerm.until()) { newTerm = it }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    contactLauncher.launch(Intent(Intent.ACTION_PICK,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }, Modifier.padding(vertical = 4.dp)) { Text("Добавить из контактов") }
                val expired = items.count { it.expired() }
                if (expired > 0) TextButton(onClick = {
                    db.blacklistPurgeExpired(); reload()
                }) { Text("Удалить истёкшие ($expired)") }
            }

            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(items) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(e.name ?: e.identity, style = MaterialTheme.typography.titleSmall)
                        if (e.name != null) Text(e.identity, style = MaterialTheme.typography.labelSmall)
                        // Как запись сопоставляется — так же подписано в «Избранных».
                        Text(com.davnozdu.autoresponder.rules.NameMatch.describe(e.identity),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fmtUntil(e.untilTs), style = MaterialTheme.typography.labelSmall,
                            color = if (e.expired()) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        TermChips(e.untilTs) { db.blacklistUpsert(e.copy(untilTs = it.until())); reload() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = e.viaLlm, onCheckedChange = { on ->
                                db.blacklistUpsert(e.copy(viaLlm = on,
                                    prompt = e.prompt ?: if (on) DEFAULT_BL_PROMPT else null)); reload()
                            })
                            Text("Отвечать через LLM")
                            Spacer(Modifier.weight(1f))
                            if (e.viaLlm) TextButton(onClick = { editing = e }) { Text("Промпт") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = e.onSms, onCheckedChange = { db.blacklistUpsert(e.copy(onSms = it)); reload() })
                            Text("SMS")
                            Spacer(Modifier.width(12.dp))
                            Checkbox(checked = e.onMsgr, onCheckedChange = { db.blacklistUpsert(e.copy(onMsgr = it)); reload() })
                            Text("Мессенджеры")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = e.onCalls, onCheckedChange = { db.blacklistUpsert(e.copy(onCalls = it)); reload() })
                            Text(if (e.onCalls) "Звонки: пропускать" else "Звонки: отклонять + SMS")
                            Spacer(Modifier.weight(1f))
                            if (!e.onCalls) TextButton(onClick = { editingCall = e }) { Text("Промпт звонка") }
                        }
                        Row {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { db.blacklistDelete(e.id); reload() }) { Text("Удалить") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { e ->
        var text by remember(e.id) { mutableStateOf(e.prompt ?: DEFAULT_BL_PROMPT) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Промпт для ${e.name ?: e.identity}") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4) },
            confirmButton = { TextButton(onClick = {
                db.blacklistUpsert(e.copy(prompt = text)); editing = null; reload()
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } }
        )
    }

    editingCall?.let { e ->
        var text by remember(e.id) { mutableStateOf(e.callPrompt ?: DEFAULT_CALL_PROMPT) }
        AlertDialog(
            onDismissRequest = { editingCall = null },
            title = { Text("Промпт звонка: ${e.name ?: e.identity}") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = { TextButton(onClick = {
                db.blacklistUpsert(e.copy(callPrompt = text)); editingCall = null; reload()
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editingCall = null }) { Text("Отмена") } }
        )
    }
}
