package com.davnozdu.autoresponder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        setContent { MaterialTheme { HistoryScreen() } }
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
    var convs by remember { mutableStateOf(listOf<HistItem>()) }
    var openNumber by remember { mutableStateOf<String?>(null) }
    var openName by remember { mutableStateOf<String?>(null) }
    var thread by remember { mutableStateOf(listOf<HistItem>()) }
    var period by remember { mutableStateOf(0) } // 0=вся,1=24ч,2=7д
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reloadConvs() {
        scope.launch { convs = withContext(Dispatchers.IO) { db.conversations(query) } }
    }
    fun loadThread(num: String) {
        val now = System.currentTimeMillis()
        val from = when (period) { 1 -> now - 86_400_000L; 2 -> now - 7 * 86_400_000L; else -> 0L }
        scope.launch { thread = withContext(Dispatchers.IO) { db.thread(num, from) } }
    }

    LaunchedEffect(query) { reloadConvs() }
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
                if (convs.isEmpty()) Text("Пусто", Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(convs) { c ->
                        Column(Modifier.fillMaxWidth().clickable {
                            openNumber = c.number; openName = c.name; summary = null
                        }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(c.name ?: c.number, style = MaterialTheme.typography.titleSmall)
                            Text("${c.channel} · ${tsFmt.format(Date(c.ts))}", style = MaterialTheme.typography.labelSmall)
                            Text(c.body.take(60), style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                        }
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
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(thread) { m ->
                        val who = if (m.direction == "in") "Клиент" else "Мы"
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("$who · ${m.channel} · ${tsFmt.format(Date(m.ts))}",
                                style = MaterialTheme.typography.labelSmall)
                            Text(m.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
