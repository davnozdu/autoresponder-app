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

data class AppInfo(val pkg: String, val label: String)

class AppPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppPickerScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen() {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    var selected by remember { mutableStateOf(s.monitoredApps) }
    val apps = remember {
        val pm = ctx.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launch, 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Приложения для автоответа") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("Отмеченные приложения будут обрабатываться (ответ через кнопку в уведомлении).",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxSize()) {
                items(apps) { a ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = a.pkg in selected, onCheckedChange = { on ->
                            val next = selected.toMutableSet()
                            if (on) next.add(a.pkg) else next.remove(a.pkg)
                            selected = next; s.monitoredApps = next
                        })
                        Column(Modifier.weight(1f)) {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            Text(a.pkg, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
