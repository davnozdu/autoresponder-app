package com.davnozdu.autoresponder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.Llm
import com.davnozdu.autoresponder.store.AboutInfo
import com.davnozdu.autoresponder.store.Holidays
import com.davnozdu.autoresponder.store.Prices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** Manual test of the configured text route. Never sends a message to a customer. */
class LlmChatActivity : ComponentActivity() {
    private val chatModel by viewModels<LlmChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { LlmChatScreen(chatModel) } }
    }
}

/** Keeps a long native inference and its answer across screen rotation. */
class LlmChatViewModel : ViewModel() {
    var question by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var turns by mutableStateOf(listOf<Pair<String, String>>())
        private set

    fun ask(ctx: android.content.Context) {
        val text = question.trim()
        if (text.isEmpty() || busy) return
        val app = ctx.applicationContext
        val settings = Settings(app)
        val previous = turns.takeLast(8)
        question = ""
        turns = turns + ("Вы" to text)
        busy = true
        viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            val answer = try {
                withContext(Dispatchers.IO) {
                    if (!settings.llmEnabled) "Включите LLM в настройках."
                    else if (!Llm.isConfigured(app)) "Модель для выбранного режима не настроена или не скачана."
                    else {
                        EventLog(app).add("Тест LLM: запрос начат, режим ${settings.llmMode}")
                        val system = buildString {
                            append(settings.promptSms).append("\n\n")
                            append("Business knowledge / FAQ:\n")
                            append(AboutInfo.text(app, settings.businessInfo)).append("\n\n")
                            append(Prices.promptBlock(app, text)).append("\n")
                            if (settings.holidaysEnabled) append(Holidays.text(app)).append("\n")
                            append("Current date/time: ").append(ZonedDateTime.now()).append("\n")
                            append("Answer the user's question in its language. Do not invent prices or order facts. " +
                                "This is a manual test, so do not claim that a message was sent to a customer.")
                        }
                        val prompt = previous.joinToString("\n") { "${it.first}: ${it.second}" } + "\nВы: $text"
                        Llm.generate(app, prompt, 2000, system) ?: "Модель не ответила. Проверьте настройки и журнал."
                    }
                }
            } catch (e: Exception) {
                EventLog(app).add("Тест LLM: ошибка ${e.javaClass.simpleName}: ${e.message}")
                "Ошибка модели: ${e.message ?: e.javaClass.simpleName}"
            }
            EventLog(app).add("Тест LLM: завершён за ${
                (android.os.SystemClock.elapsedRealtime() - started) / 1000
            } с, длина ответа ${answer.length}")
            turns = turns + ("LLM" to answer)
            busy = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmChatScreen(model: LlmChatViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(ctx) }
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(model.turns.size, model.busy, imeVisible) {
        val last = model.turns.size - 1 + if (model.busy) 1 else 0
        if (last >= 0) {
            // Recalculate after IME animation or a new answer changes the list height.
            delay(120)
            listState.animateScrollToItem(last)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Чат с LLM") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!imeVisible) {
                Text("Режим: ${when (settings.llmMode) { "local" -> "локально"; "auto" -> "авто"; else -> "облако" }}. " +
                    "Проверяет модель и базу знаний из настроек. Сообщения клиентам не отправляются.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
                items(model.turns) { (role, text) ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(role, style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(text, Modifier.fillMaxWidth(), softWrap = true,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (model.busy) item {
                    Column {
                        Text("Модель загружается или формирует ответ…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                OutlinedTextField(model.question, { model.question = it },
                    Modifier.weight(1f).heightIn(max = 140.dp),
                    placeholder = { Text("Задайте вопрос модели") }, maxLines = 4)
                Spacer(Modifier.width(8.dp))
                Button(enabled = model.question.isNotBlank() && !model.busy,
                    onClick = { model.ask(ctx) }) { Text("→") }
            }
        }
    }
}
