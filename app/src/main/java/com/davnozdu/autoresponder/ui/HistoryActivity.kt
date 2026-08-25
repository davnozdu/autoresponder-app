package com.davnozdu.autoresponder.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Sms
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.davnozdu.autoresponder.store.HistItem
import com.davnozdu.autoresponder.store.Importer
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.store.Summarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { HistoryScreen() } }
    }
}

private val tsFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val db = remember { HistoryDb.get(ctx) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) } // 0=все,1=звонки,2=смс,3=чаты
    var convs by remember { mutableStateOf(listOf<HistItem>()) }
    var openNumber by remember { mutableStateOf<String?>(null) }
    var openName by remember { mutableStateOf<String?>(null) }
    var thread by remember { mutableStateOf(listOf<HistItem>()) }
    var period by remember { mutableStateOf(0) } // 0=вся,1=24ч,2=7д
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun channelsFor(f: Int) = when (f) {
        1 -> listOf("call"); 2 -> listOf("sms", "rcs"); 3 -> listOf("whatsapp", "telegram"); else -> emptyList()
    }
    fun reloadConvs() {
        scope.launch { convs = withContext(Dispatchers.IO) { db.conversations(query, channelsFor(filter), autoOnly = filter == 4) } }
    }
    fun loadThread(num: String) {
        val now = System.currentTimeMillis()
        val from = when (period) { 1 -> now - 86_400_000L; 2 -> now - 7 * 86_400_000L; else -> 0L }
        scope.launch { thread = withContext(Dispatchers.IO) { db.thread(num, from) } }
    }

    LaunchedEffect(query, filter) { reloadConvs() }
    LaunchedEffect(openNumber, period) { openNumber?.let { loadThread(it) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (openNumber == null) "История" else (openName ?: openNumber!!)) },
            navigationIcon = {
                if (openNumber != null) TextButton(onClick = { openNumber = null; summary = null }) { Text("‹ Назад") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (openNumber == null) {
                OutlinedTextField(query, { query = it }, label = { Text("Поиск: номер или имя") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true)
                OutlinedButton(onClick = {
                    Toast.makeText(ctx, "Импортирую…", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val cnt = withContext(Dispatchers.IO) { Importer.importAll(ctx) }
                        Toast.makeText(ctx, "Импортировано: $cnt", Toast.LENGTH_LONG).show()
                        reloadConvs()
                    }
                }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("⤵ Импорт SMS и звонков из телефона") }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(filter == 0, { filter = 0 }, { Text("Все") })
                    FilterChip(filter == 1, { filter = 1 }, { Text("Звонки") })
                    FilterChip(filter == 2, { filter = 2 }, { Text("СМС") })
                    FilterChip(filter == 3, { filter = 3 }, { Text("Чаты") })
                    FilterChip(filter == 4, { filter = 4 }, { Text("🤖 Авто") })
                }
                TextButton(onClick = { confirmClear = true },
                    modifier = Modifier.padding(horizontal = 8.dp)) { Text("🗑 Очистить историю") }
                if (convs.isEmpty()) Text("Пусто", Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(convs) { c ->
                        Row(Modifier.fillMaxWidth().clickable {
                            openNumber = c.number; openName = c.name; summary = null
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val icon = when (c.channel) {
                                "call" -> Icons.Filled.Call
                                "sms", "rcs" -> Icons.Filled.Sms
                                else -> Icons.Filled.Chat
                            }
                            Icon(icon, contentDescription = c.channel, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name ?: c.number, style = MaterialTheme.typography.titleSmall)
                                Text("${c.channel} · ${tsFmt.format(Date(c.ts))}", style = MaterialTheme.typography.labelSmall)
                                Text(c.body.take(60), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(period == 0, { period = 0 }, { Text("Вся ветка") })
                    FilterChip(period == 1, { period = 1 }, { Text("24ч") })
                    FilterChip(period == 2, { period = 2 }, { Text("7 дней") })
                }
                Button(onClick = {
                    busy = true; summary = "Готовлю пересказ…"
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { Summarizer.summarize(ctx, thread) }
                        summary = res; busy = false
                    }
                }, enabled = !busy, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Краткий пересказ") }
                summary?.let {
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(it, Modifier.padding(12.dp).verticalScroll(rememberScrollState()))
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                    items(thread) { m ->
                        val out = m.direction == "out"
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
                            Surface(
                                color = if (out) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(
                                    topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = if (out) 16.dp else 4.dp,
                                    bottomEnd = if (out) 4.dp else 16.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(m.body, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${if (out) (if (m.auto) "🤖 Автоответ" else "Мы") else "Клиент"} · ${m.channel} · ${tsFmt.format(Date(m.ts))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = if (out) TextAlign.End else TextAlign.Start
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Вся сохранённая история звонков и сообщений будет удалена. Действие необратимо.") },
            confirmButton = { TextButton(onClick = {
                db.clearEvents(); confirmClear = false
                openNumber = null; summary = null; reloadConvs()
            }) { Text("Да, очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
}
