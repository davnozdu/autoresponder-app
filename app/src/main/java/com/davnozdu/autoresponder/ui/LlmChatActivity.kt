package com.davnozdu.autoresponder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.Llm
import com.davnozdu.autoresponder.store.AboutInfo
import com.davnozdu.autoresponder.store.Holidays
import com.davnozdu.autoresponder.store.Prices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** Manual test of the configured text route. Never sends a message to a customer. */
class LlmChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { LlmChatScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmChatScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(ctx) }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Чат с LLM") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("Режим: ${when (settings.llmMode) { "local" -> "локально"; "auto" -> "авто"; else -> "облако" }}. " +
                "Проверяет модель и базу знаний из настроек. Сообщения клиентам не отправляются.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                items(turns) { (role, text) ->
                    Text("$role: $text", Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                OutlinedTextField(question, { question = it }, Modifier.weight(1f),
                    placeholder = { Text("Задайте вопрос модели") })
                Spacer(Modifier.width(8.dp))
                Button(enabled = question.isNotBlank() && !busy, onClick = {
                    val text = question.trim()
                    question = ""
                    val previous = turns.takeLast(8)
                    turns = turns + ("Вы" to text)
                    busy = true
                    scope.launch {
                        val answer = withContext(Dispatchers.IO) {
                            if (!settings.llmEnabled) "Включите LLM в настройках."
                            else if (!Llm.isConfigured(ctx)) "Модель для выбранного режима не настроена или не скачана."
                            else {
                                val system = buildString {
                                    append(settings.promptSms).append("\n\n")
                                    append("Business knowledge / FAQ:\n")
                                    append(AboutInfo.text(ctx, settings.businessInfo)).append("\n\n")
                                    append(Prices.promptBlock(ctx, text)).append("\n")
                                    if (settings.holidaysEnabled) append(Holidays.text(ctx)).append("\n")
                                    append("Current date/time: ").append(ZonedDateTime.now()).append("\n")
                                    append("Answer the user's question in its language. Do not invent prices or order facts. " +
                                        "This is a manual test, so do not claim that a message was sent to a customer.")
                                }
                                val prompt = previous.joinToString("\n") { "${it.first}: ${it.second}" } + "\nВы: $text"
                                Llm.generate(ctx, prompt, 2000, system) ?: "Модель не ответила. Проверьте настройки и журнал."
                            }
                        }
                        turns = turns + ("LLM" to answer)
                        busy = false
                    }
                }) { Text("→") }
            }
        }
    }
}
