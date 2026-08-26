package com.davnozdu.autoresponder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.store.HistoryQa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { ChatScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // pair: role ("Вы"/"AI") to text
    val db = remember { HistoryDb.get(ctx) }
    var turns by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    LaunchedEffect(Unit) { turns = withContext(Dispatchers.IO) { db.qaAll() } }
    var confirmNew by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("История запросов") },
            actions = { TextButton(onClick = { confirmNew = true }) { Text("Новый чат") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (turns.isEmpty()) Text(
                "Спросите про звонки и сообщения: «когда последний раз звонил …», «сколько раз звонил …», «что хотел … за неделю».",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                items(turns) { (role, text) ->
                    val me = role == "Вы"
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (me) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp,
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (busy) item { Text("AI думает…", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall) }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f),
                    placeholder = { Text("Ваш вопрос…") })
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && input.isNotBlank(), onClick = {
                    val q = input.trim(); input = ""
                    val prior = turns              // в промпт идёт диалог ДО этого вопроса
                    turns = turns + ("Вы" to q)
                    busy = true
                    scope.launch {
                        val ans = withContext(Dispatchers.IO) {
                            db.qaAdd("Вы", q)
                            HistoryQa.ask(ctx, q, prior)
                        }
                        turns = turns + ("AI" to ans)
                        withContext(Dispatchers.IO) { db.qaAdd("AI", ans) }
                        busy = false
                    }
                }) { Text("→") }
            }
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Новый чат?") },
            text = { Text("История запросов будет очищена.") },
            confirmButton = { TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { db.qaClear() } }
                turns = emptyList(); confirmNew = false
            }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Отмена") } }
        )
    }
}
