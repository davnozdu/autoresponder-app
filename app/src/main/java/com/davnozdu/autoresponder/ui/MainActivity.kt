package com.davnozdu.autoresponder.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.LlmConfig
import com.davnozdu.autoresponder.llm.LlmFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(s.enabled) }
    var respCalls by remember { mutableStateOf(s.respondCalls) }
    var respSms by remember { mutableStateOf(s.respondSms) }
    var trigDnd by remember { mutableStateOf(s.triggerOnDnd) }
    var trigSched by remember { mutableStateOf(s.triggerOnSchedule) }
    var prefixes by remember { mutableStateOf(s.allowedPrefixes.joinToString(",")) }
    var startMin by remember { mutableStateOf(s.scheduleStartMin.toString()) }
    var endMin by remember { mutableStateOf(s.scheduleEndMin.toString()) }
    var maxReplies by remember { mutableStateOf(s.maxReplies.toString()) }
    var timeoutH by remember { mutableStateOf(s.timeoutHours.toString()) }
    var maxSeg by remember { mutableStateOf(s.maxSegments.toString()) }
    var defLang by remember { mutableStateOf(s.defaultLang) }

    var tplRu by remember { mutableStateOf(s.template("ru")) }
    var tplCs by remember { mutableStateOf(s.template("cs")) }
    var tplEn by remember { mutableStateOf(s.template("en")) }

    var llmOn by remember { mutableStateOf(s.llmEnabled) }
    var provider by remember { mutableStateOf(s.llmProvider) }
    var baseUrl by remember { mutableStateOf(s.llmBaseUrl) }
    var apiKey by remember { mutableStateOf(s.llmApiKey) }
    var model by remember { mutableStateOf(s.llmModel) }
    var models by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    Scaffold(topBar = { TopAppBar(title = { Text("AutoResponder") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SwitchRow("Включён", enabled) { enabled = it; s.enabled = it }
            SwitchRow("Отвечать на звонки", respCalls) { respCalls = it; s.respondCalls = it }
            SwitchRow("Отвечать на SMS", respSms) { respSms = it; s.respondSms = it }

            HorizontalDivider()
            Text("Когда «закрыто»", style = MaterialTheme.typography.titleMedium)
            SwitchRow("По системному режиму «Не беспокоить»", trigDnd) { trigDnd = it; s.triggerOnDnd = it }
            SwitchRow("По расписанию (минуты от полуночи)", trigSched) { trigSched = it; s.triggerOnSchedule = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startMin, { startMin = it; it.toIntOrNull()?.let { v -> s.scheduleStartMin = v } },
                    label = { Text("Начало") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(endMin, { endMin = it; it.toIntOrNull()?.let { v -> s.scheduleEndMin = v } },
                    label = { Text("Конец") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }

            HorizontalDivider()
            Text("Маска стран (префиксы через запятую)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(prefixes, { prefixes = it; s.allowedPrefixes = it.split(",").map { p -> p.trim() } },
                label = { Text("напр. +420,+7") }, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(maxReplies, { maxReplies = it; it.toIntOrNull()?.let { v -> s.maxReplies = v } },
                    label = { Text("Макс. ответов") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(timeoutH, { timeoutH = it; it.toIntOrNull()?.let { v -> s.timeoutHours = v } },
                    label = { Text("Таймаут, ч") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(maxSeg, { maxSeg = it; it.toIntOrNull()?.let { v -> s.maxSegments = v } },
                label = { Text("Макс. SMS-сегментов") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(defLang, { defLang = it.trim(); s.defaultLang = it.trim() },
                label = { Text("Язык по умолчанию (en/ru/cs)") }, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Шаблоны (офлайн)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(tplRu, { tplRu = it; s.setTemplate("ru", it) }, label = { Text("RU") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tplCs, { tplCs = it; s.setTemplate("cs", it) }, label = { Text("CS") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tplEn, { tplEn = it; s.setTemplate("en", it) }, label = { Text("EN") }, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("LLM (при интернете)", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Использовать LLM", llmOn) { llmOn = it; s.llmEnabled = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ollama", "openai", "claude").forEach { p ->
                    FilterChip(selected = provider == p, onClick = { provider = p; s.llmProvider = p },
                        label = { Text(p) })
                }
            }
            OutlinedTextField(baseUrl, { baseUrl = it; s.llmBaseUrl = it },
                label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it; s.llmApiKey = it },
                label = { Text("API key (для облака)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it; s.llmModel = it },
                label = { Text("Модель") }, modifier = Modifier.fillMaxWidth())

            Button(onClick = {
                status = "Запрашиваю модели…"
                scope.launch {
                    val list = withContext(Dispatchers.IO) {
                        try {
                            LlmFactory.create(LlmConfig(provider, baseUrl, apiKey, model)).listModels()
                        } catch (e: Exception) { emptyList() }
                    }
                    models = list
                    status = if (list.isEmpty()) "Модели не найдены (проверьте URL/ключ/сеть)"
                             else "Найдено моделей: ${list.size}"
                }
            }) { Text("Запросить все модели") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            models.forEach { m ->
                TextButton(onClick = { model = m; s.llmModel = m }) { Text(m) }
            }

            HorizontalDivider()
            Text("Разрешения и роли", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                permLauncher.launch(arrayOf(
                    Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS,
                    Manifest.permission.POST_NOTIFICATIONS
                ))
            }) { Text("Выдать разрешения") }
            Button(onClick = { requestCallScreeningRole(ctx, roleLauncher::launch) }) {
                Text("Стать приложением скрининга звонков")
            }
            Button(onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Доступ к уведомлениям") }

            HorizontalDivider()
            Text("Журнал", style = MaterialTheme.typography.titleMedium)
            var logText by remember { mutableStateOf(EventLog(ctx).all()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { logText = EventLog(ctx).all() }) { Text("Обновить") }
                OutlinedButton(onClick = { EventLog(ctx).clear(); logText = "" }) { Text("Очистить") }
            }
            Text(logText.ifBlank { "пусто" }, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun requestCallScreeningRole(ctx: Context, launch: (Intent) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = ctx.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
            && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }
}
