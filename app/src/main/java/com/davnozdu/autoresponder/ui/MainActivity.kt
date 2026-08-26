package com.davnozdu.autoresponder.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
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
import com.davnozdu.autoresponder.rules.SimUtil
import com.davnozdu.autoresponder.llm.LlmConfig
import com.davnozdu.autoresponder.llm.LlmFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppScreen() } }
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
    var notifOn by remember { mutableStateOf(s.notificationsEnabled) }
    var blnMode by remember { mutableStateOf(s.blNotifMode) }
    var blnDelay by remember { mutableStateOf(s.blNotifDelayMin.toString()) }
    var blnDaily by remember { mutableStateOf(s.blDailyTimeMin) }
    var trigDnd by remember { mutableStateOf(s.triggerOnDnd) }
    var trigSched by remember { mutableStateOf(s.triggerOnSchedule) }
    var prefixes by remember { mutableStateOf(s.allowedPrefixes.joinToString(",")) }
    var startMin by remember { mutableStateOf(s.scheduleStartMin) }
    var endMin by remember { mutableStateOf(s.scheduleEndMin) }
    var excluded by remember { mutableStateOf(s.excludedNumbers) }
    var newExcl by remember { mutableStateOf("") }
    var exStarred by remember { mutableStateOf(s.excludeStarred) }
    var exContacts by remember { mutableStateOf(s.excludeContacts) }
    var respectDnd by remember { mutableStateOf(s.respectDndPriority) }
    var promptCall by remember { mutableStateOf(s.promptCall) }
    var promptSms by remember { mutableStateOf(s.promptSms) }
    var aiPrefix by remember { mutableStateOf(s.aiPrefix) }
    var bizInfo by remember { mutableStateOf(s.businessInfo) }
    var maxReplies by remember { mutableStateOf(s.maxReplies.toString()) }
    var timeoutH by remember { mutableStateOf(s.timeoutHours.toString()) }
    var maxSeg by remember { mutableStateOf(s.maxSegments.toString()) }
    var replyDelay by remember { mutableStateOf(s.replyDelayMs.toString()) }
    var notifAge by remember { mutableStateOf(s.notifMaxAgeMin.toString()) }
    var smsSlot by remember { mutableStateOf(s.smsSlot) }
    val sims = remember { SimUtil.activeSims(ctx) }
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
    var update by remember { mutableStateOf<com.davnozdu.autoresponder.update.UpdateInfo?>(null) }
    var updBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - s.lastUpdateCheck > 86_400_000L) {
            val u = withContext(Dispatchers.IO) { try { com.davnozdu.autoresponder.update.Updater.check() } catch (e: Exception) { null } }
            s.lastUpdateCheck = System.currentTimeMillis(); update = u
        }
    }
    var llm2On by remember { mutableStateOf(s.llm2Enabled) }
    var provider2 by remember { mutableStateOf(s.llm2Provider) }
    var baseUrl2 by remember { mutableStateOf(s.llm2BaseUrl) }
    var apiKey2 by remember { mutableStateOf(s.llm2ApiKey) }
    var model2 by remember { mutableStateOf(s.llm2Model) }
    var models2 by remember { mutableStateOf(listOf<String>()) }
    var status2 by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(s.exportJson().toByteArray()) }
                Toast.makeText(ctx, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                if (s.importJson(txt)) {
                    Toast.makeText(ctx, "Настройки загружены", Toast.LENGTH_SHORT).show()
                    (ctx as? Activity)?.recreate()
                } else Toast.makeText(ctx, "Неверный файл", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (uri != null) {
            ctx.contentResolver.query(uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
                if (it.moveToFirst()) {
                    s.addExcluded(it.getString(0)); excluded = s.excludedNumbers
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("AutoResponder") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            update?.let { u ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Доступно обновление: ${u.version}", style = MaterialTheme.typography.titleMedium)
                        if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !updBusy, onClick = {
                            updBusy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val f = com.davnozdu.autoresponder.update.Updater.download(ctx, u.apkUrl)
                                    f != null && com.davnozdu.autoresponder.update.Updater.install(ctx, f)
                                }
                                updBusy = false
                                Toast.makeText(ctx, if (ok) "Устанавливаю…" else "Ошибка обновления", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(if (updBusy) "Загрузка…" else "Обновить") }
                    }
                }
            }
            SwitchRow("Включён", enabled) { enabled = it; s.enabled = it }
            SwitchRow("Отвечать на звонки", respCalls) { respCalls = it; s.respondCalls = it }
            SwitchRow("Отвечать на SMS", respSms) { respSms = it; s.respondSms = it }
            SwitchRow("Уведомления (сводка, статус DND)", notifOn) {
                notifOn = it; s.notificationsEnabled = it
                if (!it) com.davnozdu.autoresponder.notif.AutoNotifications.cancelDnd(ctx)
            }
            Text("Уведомления о чёрном списке", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(blnMode == 0, { blnMode = 0; s.blNotifMode = 0 }, { Text("Выкл") })
                FilterChip(blnMode == 1, { blnMode = 1; s.blNotifMode = 1 }, { Text("Через N мин") })
                FilterChip(blnMode == 2, { blnMode = 2; s.blNotifMode = 2 }, { Text("Сводка за день") })
            }
            if (blnMode == 1) OutlinedTextField(blnDelay, { blnDelay = it; it.toIntOrNull()?.let { v -> s.blNotifDelayMin = v } },
                label = { Text("Через сколько минут уведомить") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (blnMode == 2) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Время сводки:")
                OutlinedButton(onClick = { pickTime(ctx, blnDaily) { blnDaily = it; s.blDailyTimeMin = it } }) { Text(fmtMin(blnDaily)) }
            }

            HorizontalDivider()
            Text("Когда «закрыто»", style = MaterialTheme.typography.titleMedium)
            SwitchRow("По системному режиму «Не беспокоить»", trigDnd) { trigDnd = it; s.triggerOnDnd = it }
            SwitchRow("По расписанию", trigSched) { trigSched = it; s.triggerOnSchedule = it }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Закрыто с")
                OutlinedButton(onClick = {
                    pickTime(ctx, startMin) { startMin = it; s.scheduleStartMin = it }
                }) { Text(fmtMin(startMin)) }
                Text("до")
                OutlinedButton(onClick = {
                    pickTime(ctx, endMin) { endMin = it; s.scheduleEndMin = it }
                }) { Text(fmtMin(endMin)) }
            }
            Text(
                if (startMin > endMin) "Активно ${fmtMin(startMin)} → ${fmtMin(endMin)} (через полночь)"
                else "Активно ${fmtMin(startMin)} → ${fmtMin(endMin)}",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()
            Text("Маска стран (префиксы через запятую)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(prefixes, { prefixes = it; s.allowedPrefixes = it.split(",").map { p -> p.trim() } },
                label = { Text("напр. +420,+7") }, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Избранные (не отвечать)", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Не отвечать звёздным контактам", exStarred) { exStarred = it; s.excludeStarred = it }
            SwitchRow("Не отвечать всем контактам из книги", exContacts) { exContacts = it; s.excludeContacts = it }
            SwitchRow("Уважать приоритетных в «Не беспокоить»", respectDnd) { respectDnd = it; s.respectDndPriority = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newExcl, { newExcl = it }, label = { Text("Номер") },
                    modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (newExcl.isNotBlank()) { s.addExcluded(newExcl); excluded = s.excludedNumbers; newExcl = "" }
                }) { Text("+") }
            }
            OutlinedButton(onClick = {
                contactLauncher.launch(
                    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }) { Text("Добавить из контактов") }
            excluded.forEach { num ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(num)
                    TextButton(onClick = { s.removeExcluded(num); excluded = s.excludedNumbers }) { Text("Удалить") }
                }
            }

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
            OutlinedTextField(replyDelay, { replyDelay = it; it.toLongOrNull()?.let { v -> s.replyDelayMs = v } },
                label = { Text("Задержка перед авто-ответом, мс") }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Пауза перед отправкой SMS (звонок и SMS). По умолчанию 1500") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(notifAge, { notifAge = it; it.toIntOrNull()?.let { v -> s.notifMaxAgeMin = v } },
                label = { Text("Не отвечать на сообщения старше, мин") }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Защита от старых/восстановленных после перезагрузки. По умолчанию 5") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

            Text("SIM для отправки", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = smsSlot == 1, onClick = { smsSlot = 1; s.smsSlot = 1 },
                    label = { Text("SIM 2") })
                FilterChip(selected = smsSlot == 0, onClick = { smsSlot = 0; s.smsSlot = 0 },
                    label = { Text("SIM 1") })
                FilterChip(selected = smsSlot < 0, onClick = { smsSlot = -1; s.smsSlot = -1 },
                    label = { Text("Системное") })
            }
            if (sims.isEmpty()) Text("SIM не определены (нужно READ_PHONE_STATE)",
                style = MaterialTheme.typography.bodySmall)
            else sims.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall) }
            Text("«Системное» — ответ уходит с той SIM, на которую пришло SMS (для звонков — системная по умолчанию).",
                style = MaterialTheme.typography.labelSmall)
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
                listOf("ollama", "openai", "claude", "gemini", "deepseek").forEach { p ->
                    FilterChip(selected = provider == p, onClick = {
                        provider = p; s.llmProvider = p
                        baseUrl = LlmFactory.defaultBaseUrl(p); s.llmBaseUrl = baseUrl
                    }, label = { Text(p) })
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
                    val res = withContext(Dispatchers.IO) {
                        try {
                            Result.success(LlmFactory.create(LlmConfig(provider, baseUrl, apiKey, model)).listModels())
                        } catch (e: Exception) { Result.failure<List<String>>(e) }
                    }
                    res.onSuccess { list ->
                        models = list
                        status = if (list.isEmpty()) "Пусто: сервер ответил, но моделей нет (проверьте URL/ключ)"
                                 else "Найдено моделей: ${list.size}"
                    }.onFailure { e ->
                        models = emptyList()
                        status = "Ошибка: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }) { Text("Запросить все модели") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            models.forEach { m ->
                TextButton(onClick = { model = m; s.llmModel = m }) { Text(m) }
            }

            HorizontalDivider()
            Text("Резервная модель (если основная недоступна)", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Использовать резервную", llm2On) { llm2On = it; s.llm2Enabled = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ollama", "openai", "claude", "gemini", "deepseek").forEach { p ->
                    FilterChip(selected = provider2 == p, onClick = {
                        provider2 = p; s.llm2Provider = p
                        baseUrl2 = LlmFactory.defaultBaseUrl(p); s.llm2BaseUrl = baseUrl2
                    }, label = { Text(p) })
                }
            }
            OutlinedTextField(baseUrl2, { baseUrl2 = it; s.llm2BaseUrl = it },
                label = { Text("Base URL (резерв)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey2, { apiKey2 = it; s.llm2ApiKey = it },
                label = { Text("API key (резерв)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model2, { model2 = it; s.llm2Model = it },
                label = { Text("Модель (резерв)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                status2 = "Запрашиваю модели…"
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        try { Result.success(LlmFactory.create(LlmConfig(provider2, baseUrl2, apiKey2, model2)).listModels()) }
                        catch (e: Exception) { Result.failure<List<String>>(e) }
                    }
                    res.onSuccess { list -> models2 = list
                        status2 = if (list.isEmpty()) "Пусто (проверьте URL/ключ)" else "Найдено: ${list.size}"
                    }.onFailure { e -> models2 = emptyList(); status2 = "Ошибка: ${e.message}" }
                }
            }) { Text("Запросить модели (резерв)") }
            if (status2.isNotBlank()) Text(status2, style = MaterialTheme.typography.bodySmall)
            models2.forEach { m -> TextButton(onClick = { model2 = m; s.llm2Model = m }) { Text(m) } }

            HorizontalDivider()
            Text("Промпты AI", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(aiPrefix, { aiPrefix = it; s.aiPrefix = it },
                label = { Text("Префикс (пометка ИИ)") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true)
            OutlinedTextField(promptSms, { promptSms = it; s.promptSms = it },
                label = { Text("Промпт для SMS") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3)
            OutlinedTextField(promptCall, { promptCall = it; s.promptCall = it },
                label = { Text("Промпт для звонков") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3)
            OutlinedTextField(bizInfo, { bizInfo = it; s.businessInfo = it },
                label = { Text("Факты о компании (база знаний)") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3)
            TextButton(onClick = {
                promptSms = com.davnozdu.autoresponder.data.Settings.DEF_PROMPT_SMS
                promptCall = com.davnozdu.autoresponder.data.Settings.DEF_PROMPT_CALL
                aiPrefix = com.davnozdu.autoresponder.data.Settings.DEF_AI_PREFIX
                bizInfo = com.davnozdu.autoresponder.data.Settings.DEF_BUSINESS_INFO
                s.promptSms = promptSms; s.promptCall = promptCall; s.aiPrefix = aiPrefix; s.businessInfo = bizInfo
            }) { Text("Сбросить промпты по умолчанию") }

            HorizontalDivider()
            Text("Разрешения и роли", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val perms = arrayOf(
                    Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS,
                    Manifest.permission.POST_NOTIFICATIONS)
                val missing = perms.filter {
                    ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty())
                    Toast.makeText(ctx, "Все разрешения уже выданы ✓", Toast.LENGTH_SHORT).show()
                else permLauncher.launch(missing.toTypedArray())
            }) { Text("Выдать разрешения") }
            Button(onClick = {
                val rm = ctx.getSystemService(RoleManager::class.java)
                when {
                    rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ->
                        Toast.makeText(ctx, "Роль недоступна на этом устройстве", Toast.LENGTH_SHORT).show()
                    rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ->
                        Toast.makeText(ctx, "Уже назначено приложением скрининга ✓", Toast.LENGTH_SHORT).show()
                    else -> roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                }
            }) { Text("Стать приложением скрининга звонков") }
            Button(onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Доступ к уведомлениям") }
            Button(onClick = {
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                if (pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Toast.makeText(ctx, "Оптимизация батареи уже отключена ✓", Toast.LENGTH_SHORT).show()
                } else try {
                    ctx.startActivity(Intent(
                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + ctx.packageName)))
                } catch (e: Exception) {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) { Text("Отключить оптимизацию батареи") }
            Button(onClick = {
                Toast.makeText(ctx, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val u = withContext(Dispatchers.IO) { try { com.davnozdu.autoresponder.update.Updater.check() } catch (e: Exception) { null } }
                    s.lastUpdateCheck = System.currentTimeMillis(); update = u
                    if (u == null) Toast.makeText(ctx, "Установлена последняя версия ✓", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Проверить обновление") }

            HorizontalDivider()
            Button(onClick = { ctx.startActivity(Intent(ctx, HistoryActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()) { Text("💬 История общения") }
            Button(onClick = { ctx.startActivity(Intent(ctx, HistoryChatActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()) { Text("🤖 История запросов (чат с AI)") }
            Button(onClick = { ctx.startActivity(Intent(ctx, BlacklistActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()) { Text("🚫 Чёрный список") }
            Button(onClick = { ctx.startActivity(Intent(ctx, AppPickerActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()) { Text("📱 Приложения для автоответа") }

            HorizontalDivider()
            Text("Импорт / экспорт настроек", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("autoresp", s.exportJson()))
                    Toast.makeText(ctx, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
                }) { Text("Копировать") }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                    if (txt.isNotBlank() && s.importJson(txt)) {
                        Toast.makeText(ctx, "Загружено из буфера", Toast.LENGTH_SHORT).show()
                        (ctx as? Activity)?.recreate()
                    } else Toast.makeText(ctx, "В буфере нет валидных настроек", Toast.LENGTH_SHORT).show()
                }) { Text("Вставить") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportFileLauncher.launch("autoresp-settings.json") }) { Text("Сохранить в файл") }
                OutlinedButton(onClick = { importFileLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("Загрузить из файла") }
            }

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

private fun fmtMin(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun pickTime(ctx: Context, minutes: Int, onSet: (Int) -> Unit) {
    val h = minutes / 60; val m = minutes % 60
    android.app.TimePickerDialog(ctx, { _, hh, mm -> onSet(hh * 60 + mm) }, h, m, true).show()
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
