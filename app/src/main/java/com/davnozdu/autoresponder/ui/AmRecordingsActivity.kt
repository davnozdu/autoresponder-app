package com.davnozdu.autoresponder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.LocalTranscriber
import com.davnozdu.autoresponder.llm.Transcriber
import com.davnozdu.autoresponder.store.AmRec
import com.davnozdu.autoresponder.store.HistoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
private val amSearchFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
private val amIsoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun fmtDur(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
}
private fun fmtPosition(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
private fun reasonLabel(reason: String?): String = when (reason) {
    "blacklist" -> "ЧС"
    "voicemail" -> "голосовая почта"
    "voicemail_full" -> "полная запись звонка"
    "messenger" -> "мессенджер"
    "call" -> "звонок"
    else -> "нерабочее"
}

// Источник записи — для цветовой пометки и группировки. Порядок = порядок групп в списке.
private fun sourceKey(rec: AmRec): Int = when {
    rec.reason == "call" -> 3
    rec.reason == "messenger" -> {
        val n = (rec.name ?: "").lowercase(Locale.getDefault())
        when { "whatsapp" in n -> 0; "telegram" in n -> 1; else -> 2 }
    }
    else -> 4
}
private fun sourceTitle(key: Int): String = when (key) {
    0 -> "WhatsApp"; 1 -> "Telegram"; 2 -> "Мессенджер"; 3 -> "Телефон"; else -> "Автоответчик"
}
private fun sourceColor(key: Int): Color = when (key) {
    0 -> Color(0xFF25D366)   // WhatsApp — зелёный
    1 -> Color(0xFF229ED9)   // Telegram — синий
    2 -> Color(0xFF00897B)   // прочие мессенджеры — бирюзовый
    3 -> Color(0xFFF57C00)   // телефон — оранжевый
    else -> Color(0xFF9E9E9E) // автоответчик — серый
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmRecordingsScreen() {
    val ctx = LocalContext.current
    val db = remember { HistoryDb.get(ctx) }
    val s = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var recs by remember { mutableStateOf(listOf<AmRec>()) }
    var selectedId by remember { mutableStateOf(-1L) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val player = remember { MediaPlayer() }
    // Дешифровка: какая запись раскрыта (показывает текст) и какая сейчас в процессе запроса
    // к API — раздельно, чтобы можно было развернуть УЖЕ закэшированный текст мгновенно, не
    // трогая сеть, а спиннер показывать только для реального сетевого запроса.
    var expandedId by remember { mutableStateOf(-1L) }
    var transcribingId by remember { mutableStateOf(-1L) }
    var transcribeError by remember { mutableStateOf<Pair<Long, String>?>(null) }

    fun reload() { scope.launch { recs = withContext(Dispatchers.IO) {
        runCatching { com.davnozdu.autoresponder.msgrec.MsgrRecordingRecovery.run(ctx) }
        // Pull in the dialer's own call recordings (and messenger ones) so they can be
        // transcribed / saved / shared here without digging into the dialer.
        runCatching { com.davnozdu.autoresponder.call.CallRecordingImporter.run(ctx) }
        db.amRecList(limit = 5000)
    } } }
    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(selectedId, isPlaying) {
        while (isPlaying) {
            if (!seeking) positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            delay(250)
        }
    }

    val shown = remember(recs, query) {
        val terms = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) recs else recs.filter { r ->
            val haystack = listOfNotNull(r.name, r.number, r.file?.substringAfterLast('/'),
                amSearchFmt.format(Date(r.ts)), amIsoFmt.format(Date(r.ts)),
                reasonLabel(r.reason)).joinToString(" ").lowercase(Locale.getDefault())
            terms.all { it in haystack }
        }
    }

    fun transcribeOrToggle(rec: AmRec) {
        if (rec.transcript != null) {
            expandedId = if (expandedId == rec.id) -1L else rec.id
            return
        }
        val path = rec.file
        if (path.isNullOrBlank() || !File(path).exists()) return
        transcribeError = null
        transcribingId = rec.id
        // Длинные разговоры (например, 8-минутный звонок) облачные API расшифровки не тянут
        // (лимит размера/длительности файла, таймаут). Всё, что длиннее минуты, автоматически
        // отправляем во встроенную локальную модель — она без лимитов и работает офлайн.
        val useLocal = s.transcribeProvider == "local" || rec.durationMs > 60_000L
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (useLocal)
                        LocalTranscriber.transcribe(ctx, File(path))
                    else
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
        if (selectedId == rec.id) {
            if (isPlaying) {
                runCatching { player.pause() }
                isPlaying = false
            } else {
                runCatching {
                    if (positionMs >= durationMs - 200) player.seekTo(0)
                    player.start()
                    isPlaying = true
                }
            }
            return
        }
        if (path.isNullOrBlank() || !File(path).exists()) return
        runCatching {
            player.reset()
            player.setDataSource(path)
            player.setOnCompletionListener { isPlaying = false; positionMs = durationMs }
            player.prepare()
            durationMs = player.duration.coerceAtLeast(0)
            positionMs = 0
            selectedId = rec.id
            player.start()
            isPlaying = true
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
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Поиск по абоненту, мессенджеру или дате") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            )
            if (shown.isEmpty() && recs.isNotEmpty()) Text("Ничего не найдено", Modifier.padding(16.dp))
            val grouped = remember(shown) {
                shown.groupBy { sourceKey(it) }.entries.sortedBy { it.key }.map { it.key to it.value }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (key, rows) ->
                    item(key = "hdr-$key") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(sourceColor(key)))
                            Spacer(Modifier.width(8.dp))
                            Text("${sourceTitle(key)} · ${rows.size}",
                                style = MaterialTheme.typography.titleSmall, color = sourceColor(key))
                        }
                    }
                items(rows, key = { it.id }) { r ->
                    val hasFile = !r.file.isNullOrBlank() && File(r.file).exists()
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                      Box(Modifier.width(4.dp).fillMaxHeight().background(sourceColor(key)))
                      Column(Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledIconButton(onClick = { toggle(r) }, enabled = hasFile) {
                                Icon(if (selectedId == r.id && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (selectedId == r.id && isPlaying) "Пауза" else "Играть")
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
                                        " · " + fmtDur(r.durationMs) + " · " + reasonLabel(r.reason),
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
                        if (selectedId == r.id && hasFile) {
                            Slider(
                                value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                                onValueChange = { seeking = true; positionMs = it.toInt() },
                                onValueChangeFinished = {
                                    runCatching { player.seekTo(positionMs) }
                                    seeking = false
                                },
                                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            )
                            Text("${fmtPosition(positionMs.toLong())} / ${fmtPosition(durationMs.toLong())}",
                                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        if (expandedId == r.id && r.transcript != null) {
                            Column(Modifier.fillMaxWidth().padding(start = 62.dp, end = 14.dp, bottom = 10.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    SelectionContainer(Modifier.weight(1f)) {
                                        Text(r.transcript, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    IconButton(onClick = {
                                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("transcript", r.transcript))
                                        Toast.makeText(ctx, "Текст скопирован", Toast.LENGTH_SHORT).show()
                                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать") }
                                }
                                // Длинные разговоры удобнее не читать в списке, а сохранить в файл
                                // или переслать (например, в Telegram) целиком.
                                Row {
                                    TextButton(onClick = { saveTranscript(ctx, r) }) {
                                        Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp)); Text("Сохранить .txt")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { shareTranscript(ctx, r) }) {
                                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp)); Text("Поделиться")
                                    }
                                }
                            }
                        }
                      }
                    }
                    HorizontalDivider()
                }
                }
            }
        }
    }
}

/** ASCII-only base name for a transcript file (see the English-file-names rule). */
private fun transcriptBaseName(rec: AmRec): String {
    val who = (rec.name ?: rec.number ?: "recording").replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.').take(40).ifEmpty { "recording" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(rec.ts))
    return "${who}_$stamp"
}

/** Persist the transcript as a .txt the owner can keep, so a long call need not be read in the list. */
private fun saveTranscript(ctx: Context, rec: AmRec) {
    val text = rec.transcript ?: return
    runCatching {
        val dir = File("/sdcard/AutoResponder/transcripts").apply { mkdirs() }
        val file = File(dir, "${transcriptBaseName(rec)}.txt")
        file.writeText(text)
        Toast.makeText(ctx, "Сохранено: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }.onFailure {
        Toast.makeText(ctx, "Не удалось сохранить: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

/** Share the transcript as a .txt attachment (works for long texts and forwards cleanly to Telegram). */
private fun shareTranscript(ctx: Context, rec: AmRec) {
    val text = rec.transcript ?: return
    runCatching {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${transcriptBaseName(rec)}.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Поделиться расшифровкой")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(ctx, "Не удалось поделиться: ${it.message}", Toast.LENGTH_LONG).show()
    }
}
