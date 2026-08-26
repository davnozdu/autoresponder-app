package com.davnozdu.autoresponder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.store.HistItem
import com.davnozdu.autoresponder.store.HistoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val from = intent.getLongExtra("from", 0L)
        setContent { AppTheme { StatsScreen(from) } }
    }
}

private val sFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(from: Long) {
    val ctx = LocalContext.current
    // Запрос в IO: на большой истории чтение в композиции подвешивало экран.
    var items by remember { mutableStateOf(listOf<HistItem>()) }
    LaunchedEffect(from) {
        items = withContext(Dispatchers.IO) { HistoryDb.get(ctx).autoReplies(from) }
    }
    val sms = items.count { it.channel == "sms" }
    val calls = items.count { it.channel == "call" }
    val msgr = items.size - sms - calls

    Scaffold(topBar = { TopAppBar(title = { Text("Сводка автоответа") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Отвечено роботом", style = MaterialTheme.typography.titleMedium)
                    Text("SMS: $sms · Сообщения: $msgr · Звонки: $calls",
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (items.isEmpty()) Text("Нет авто-ответов за период.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(items) { m: HistItem ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top) {
                        val icon = when (m.channel) {
                            "call" -> Icons.Filled.Call; "sms", "rcs" -> Icons.Filled.Sms; else -> Icons.Filled.Chat
                        }
                        Icon(icon, contentDescription = m.channel, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name ?: m.number, style = MaterialTheme.typography.titleSmall)
                            Text("${m.channel} · ${sFmt.format(Date(m.ts))}", style = MaterialTheme.typography.labelSmall)
                            Text(m.body, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
