package com.davnozdu.autoresponder.ui

import android.content.Intent
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
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.msgrec.MsgrCaptureManager

/**
 * Настройка записи звонков в мессенджерах: общий тумблер + выбор установленных приложений галочками.
 * Изменения сразу применяются к живому захвату (MsgrCaptureManager.refresh).
 */
class MsgrAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { MsgrAppsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsgrAppsScreen() {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    var enabled by remember { mutableStateOf(s.msgrRecEnabled) }
    var selected by remember { mutableStateOf(s.msgrRecApps) }
    var answering by remember { mutableStateOf(s.msgrAmEnabled) }
    val apps = remember {
        val pm = ctx.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val fromLauncher = pm.queryIntentActivities(launch, 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        val extra = s.msgrRecApps.filter { m -> fromLauncher.none { it.pkg == m } }.map { pkg ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            AppInfo(pkg, label)
        }
        (fromLauncher + extra).distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Запись звонков в мессенджерах") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Записывать звонки", style = MaterialTheme.typography.bodyLarge)
                    Text(if (enabled) "Включено" else "Выключено", style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it; s.msgrRecEnabled = it; MsgrCaptureManager.refresh(ctx)
                })
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Принимать звонки и говорить приветствие", style = MaterialTheme.typography.bodyLarge)
                    Text("Telegram, WhatsApp и WhatsApp Business: в рабочем режиме — скрининг звонка, при «Не беспокоить» — автоответчик. Общие расписания, приветствия и исключения; избранные звонят обычно.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = answering, onCheckedChange = {
                    answering = it; s.msgrAmEnabled = it
                    if (!it) com.davnozdu.autoresponder.msgrec.MsgrAnswerManager.cancel()
                })
            }
            HorizontalDivider()
            Text("Отмеченные приложения будут записываться автоматически. Запись обеих сторон, " +
                "сохраняется в папку Messenger, доступна для расшифровки в журнале.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) {
                items(apps) { a ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = a.pkg in selected, enabled = enabled || answering, onCheckedChange = { on ->
                            val next = selected.toMutableSet()
                            if (on) next.add(a.pkg) else next.remove(a.pkg)
                            selected = next; s.msgrRecApps = next; MsgrCaptureManager.refresh(ctx)
                        })
                        Column(Modifier.weight(1f)) {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            Text(a.pkg, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
            // §7 GPLv3 additional-terms attribution (CallVault fork).
            Text("Механизм записи основан на CallVault (GPL-3.0): github.com/madkongo/CallVault",
                Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
