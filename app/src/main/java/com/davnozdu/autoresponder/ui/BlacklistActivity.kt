package com.davnozdu.autoresponder.ui

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlacklistScreen() {
    val ctx = LocalContext.current
    val db = remember { HistoryDb.get(ctx) }
    var items by remember { mutableStateOf(listOf<BlackEntry>()) }
    var newId by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<BlackEntry?>(null) }
    var editingCall by remember { mutableStateOf<BlackEntry?>(null) }

    fun reload() { items = db.blacklistAll() }
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
                    db.blacklistUpsert(BlackEntry(0, it.getString(0), it.getString(1), false, null))
                    reload()
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Чёрный список") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            Text("Кому не хотим отвечать. Галочка «через LLM» — вместо тишины отвечать своим промптом.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newId, { newId = it }, label = { Text("Номер или имя") },
                    modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = {
                    if (newId.isNotBlank()) { db.blacklistUpsert(BlackEntry(0, newId.trim(), null, false, null)); newId = ""; reload() }
                }) { Text("+") }
            }
            OutlinedButton(onClick = {
                contactLauncher.launch(Intent(Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }, Modifier.padding(vertical = 4.dp)) { Text("Добавить из контактов") }

            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(items) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(e.name ?: e.identity, style = MaterialTheme.typography.titleSmall)
                        if (e.name != null) Text(e.identity, style = MaterialTheme.typography.labelSmall)
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
