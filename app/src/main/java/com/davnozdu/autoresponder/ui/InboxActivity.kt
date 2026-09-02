package com.davnozdu.autoresponder.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.store.PendingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Требуют ответа» — ветки, где последнее слово осталось за клиентом.
 *
 * Автоответ закрывает разговор только по форме: клиенту сказали, что ответят в рабочее
 * время, и это обещание кто-то должен выполнить. Раньше список таких людей приходилось
 * восстанавливать по памяти, листая историю.
 */
class InboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { InboxScreen() } }
    }
}

private val tsFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

private fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 60 -> "$m мин назад"
        m < 24 * 60 -> "${m / 60} ч назад"
        else -> "${m / (24 * 60)} дн назад"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InboxScreen() {
    val ctx = LocalContext.current
    val db = remember { HistoryDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<PendingItem>()) }
    var days by remember { mutableStateOf(1) }          // окно: 1 / 3 / 7 суток
    var blockWho by remember { mutableStateOf<Pair<String, String?>?>(null) }

    fun reload() {
        scope.launch {
            val from = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            items = withContext(Dispatchers.IO) { db.needsAnswer(from) }
        }
    }
    LaunchedEffect(days) { reload() }

    Scaffold(topBar = { TopAppBar(title = { Text("Требуют ответа") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("Только те, кому ответил робот, а вы — ещё нет. Рассылки и переписка, "
                + "на которую автоответчик не отвечал, сюда не попадают. Ветка уходит из "
                + "списка сама, когда вы отправите SMS или перезвоните.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(days == 1, { days = 1 }, { Text("Сутки") })
                FilterChip(days == 3, { days = 3 }, { Text("3 дня") })
                FilterChip(days == 7, { days = 7 }, { Text("Неделя") })
            }
            if (items.isEmpty()) Text("Все ответы даны ✓", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(items) { p ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(p.name ?: p.number, style = MaterialTheme.typography.titleSmall)
                        Text("${p.channel} · ${tsFmt.format(Date(p.lastIn))} (${ago(p.lastIn)})"
                            + if (p.incoming > 1) " · обращений: ${p.incoming}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.body.isNotBlank())
                            Text(p.body.take(120), style = MaterialTheme.typography.bodySmall)
                        // FlowRow, а не Row: четыре кнопки на узком экране или при
                        // крупном системном шрифте уезжают за край, и «Готово» —
                        // единственное, ради чего экран и нужен, — становится недоступным.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.Center) {
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_DIAL,
                                    Uri.parse("tel:" + p.number)))
                            }) { Text("Позвонить") }
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_SENDTO,
                                    Uri.parse("smsto:" + p.number)))
                            }) { Text("SMS") }
                            TextButton(onClick = { blockWho = p.number to p.name }) { Text("В ЧС") }
                            TextButton(onClick = {
                                // «Занялся» — отметка временем последнего входящего: клиент
                                // напишет снова, и ветка вернётся в список.
                                db.inboxDone(p.number, p.lastIn); reload()
                            }) { Text("Готово") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    blockWho?.let { (num, name) ->
        BlockDialog(num, name, onDismiss = { blockWho = null }) { blockWho = null; reload() }
    }
}
