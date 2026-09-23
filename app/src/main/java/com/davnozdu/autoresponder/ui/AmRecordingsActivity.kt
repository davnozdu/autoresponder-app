package com.davnozdu.autoresponder.ui

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val scope = rememberCoroutineScope()

    var recs by remember { mutableStateOf(listOf<AmRec>()) }
    var playingId by remember { mutableStateOf(-1L) }
    val player = remember { MediaPlayer() }

    fun reload() { scope.launch { recs = withContext(Dispatchers.IO) { db.amRecList() } } }
    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }

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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledIconButton(onClick = { toggle(r) }, enabled = hasFile) {
                            Icon(Icons.Filled.PlayArrow,
                                contentDescription = if (playingId == r.id) "Стоп" else "Играть")
                        }
                        Spacer(Modifier.width(12.dp))
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
                                    (if (r.reason == "blacklist") " · ЧС" else " · нерабочее"),
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (!hasFile) Text("файл не найден",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
