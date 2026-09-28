package com.davnozdu.autoresponder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.Transcriber
import com.davnozdu.autoresponder.store.AmRec
import com.davnozdu.autoresponder.store.HistoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Записи голосового автоответчика: список со встроенным плеером — слушать, не роясь в папках. */
class AmRecordingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AmRecordingsScreen() } }
    }
}

private val amFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

private fun fmtDur(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmRecordingsScreen() {
    val ctx = LocalContext.current
    val db = remember { HistoryDb.get(ctx) }
    val s = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var recs by remember { mutableStateOf(listOf<AmRec>()) }
    var playingId by remember { mutableStateOf(-1L) }
    val player = remember { MediaPlayer() }
    // Дешифровка: какая запись раскрыта (показывает текст) и какая сейчас в процессе запроса
    // к API — раздельно, чтобы можно было развернуть УЖЕ закэшированный текст мгновенно, не
    // трогая сеть, а спиннер показывать только для реального сетевого запроса.
    var expandedId by remember { mutableStateOf(-1L) }
    var transcribingId by remember { mutableStateOf(-1L) }
    var transcribeError by remember { mutableStateOf<Pair<Long, String>?>(null) }

    fun reload() { scope.launch { recs = withContext(Dispatchers.IO) { db.amRecList() } } }
    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }

    fun transcribeOrToggle(rec: AmRec) {
        if (rec.transcript != null) {
            expandedId = if (expandedId == rec.id) -1L else rec.id
            return
        }
        val path = rec.file
        if (path.isNullOrBlank() || !File(path).exists()) return
        transcribeError = null
        transcribingId = rec.id
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Transcriber.transcribe(s.transcribeProvider, s.transcribeApiKey, s.transcribeModel, File(path))
                }
            }
            transcribingId = -1L
            result.onSuccess { text ->
                withContext(Dispatchers.IO) { db.amRecSetTranscript(rec.id, text) }
                reload()
                expandedId = rec.id
            }.onFailure { e ->
                transcribeError = rec.id to (e.message ?: "Ошибка дешифровки")
            }
        }
    }

    fun toggle(rec: AmRec) {
        val path = rec.file
        if (playingId == rec.id) {
            runCatching { player.stop() }; runCatching { player.reset() }; playingId = -1
            return
        }
        if (path.isNullOrBlank() || !File(path).exists()) return
        runCatching {
            player.reset()
            player.setDataSource(path)
            player.setOnCompletionListener { playingId = -1 }
            player.prepare()
            player.start()
            playingId = rec.id
        }.onSuccess {
            if (!rec.heard) { scope.launch { withContext(Dispatchers.IO) { db.amRecMarkHeard(rec.id) }; reload() } }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Автоответчик") },
            actions = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { db.amRecMarkAllHeard() }; reload() }
                }) { Text("Всё прочитано") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (recs.isEmpty()) Text("Записей пока нет", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(recs) { r ->
                    val hasFile = !r.file.isNullOrBlank() && File(r.file).exists()
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledIconButton(onClick = { toggle(r) }, enabled = hasFile) {
                                Icon(Icons.Filled.PlayArrow,
                                    contentDescription = if (playingId == r.id) "Стоп" else "Играть")
                            }
                            Spacer(Modifier.width(8.dp))
                            // Рядом с Play — маленькая кнопка дешифровки: та же запись, что уже
                            // дешифрована, просто разворачивает закэшированный текст без сети;
                            // не дешифрованная — идёт в Transcriber и кэшируется в БД.
                            if (transcribingId == r.id) {
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                IconButton(onClick = { transcribeOrToggle(r) }, enabled = hasFile) {
                                    Icon(Icons.Filled.Description,
                                        contentDescription = "Дешифровать в текст",
                                        tint = if (r.transcript != null) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!r.heard) {
                                        Badge(Modifier.padding(end = 6.dp)) { Text("новое") }
                                    }
                                    Text(
                                        r.name ?: r.number ?: "Неизвестный",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                Text(
                                    (r.number ?: "—") + " · " + amFmt.format(Date(r.ts)) +
                                        " · " + fmtDur(r.durationMs) +
                                        " · " + when (r.reason) {
                                            "blacklist" -> "ЧС"
                                            "voicemail" -> "голосовая почта"
                                            "voicemail_full" -> "полная запись звонка"
                                            else -> "нерабочее"
                                        },
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (!hasFile) Text("файл не найден",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                                if (transcribeError?.first == r.id) Text(
                                    "Дешифровка не удалась: ${transcribeError?.second}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (expandedId == r.id && r.transcript != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 62.dp, end = 14.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(r.transcript, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("transcript", r.transcript))
                                    Toast.makeText(ctx, "Текст скопирован", Toast.LENGTH_SHORT).show()
                                }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
