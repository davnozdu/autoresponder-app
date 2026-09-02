package com.davnozdu.autoresponder.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var priceSrc by remember { mutableStateOf(com.davnozdu.autoresponder.store.Prices.source(ctx)) }
    var priceCount by remember { mutableStateOf(com.davnozdu.autoresponder.store.Prices.rows(ctx).size) }
    var smsCmdOn by remember { mutableStateOf(s.smsCommandsOn) }
    var smsCmdNums by remember { mutableStateOf(s.smsCommandNumbers) }
    var newCmdNum by remember { mutableStateOf("") }
    var digestOn by remember { mutableStateOf(s.digestEnabled) }
    var digestHour by remember { mutableStateOf(s.digestHour) }
    var quietOn by remember { mutableStateOf(s.quietHoursOn) }
    var quietStart by remember { mutableStateOf(s.quietStartMin) }
    var quietEnd by remember { mutableStateOf(s.quietEndMin) }
    var respSms by remember { mutableStateOf(s.respondSms) }
    var notifOn by remember { mutableStateOf(s.notificationsEnabled) }
    var blnMode by remember { mutableStateOf(s.blNotifMode) }
    var blnDelay by remember { mutableStateOf(s.blNotifDelayMin.toString()) }
    var blnDaily by remember { mutableStateOf(s.blDailyTimeMin) }
    var trigDnd by remember { mutableStateOf(s.triggerOnDnd) }
    var trigSched by remember { mutableStateOf(s.triggerOnSchedule) }
    var sim1On by remember { mutableStateOf(s.sim1Enabled) }
    var sim2On by remember { mutableStateOf(s.sim2Enabled) }
    var prefixes1 by remember { mutableStateOf(s.prefixesRaw(0).joinToString(",")) }
    var prefixes2 by remember { mutableStateOf(s.prefixesRaw(1).joinToString(",")) }
    var schedMode by remember { mutableStateOf(s.scheduleMode) }
    var workStart by remember { mutableStateOf(s.workStartMin) }
    var workEnd by remember { mutableStateOf(s.workEndMin) }
    var workDays by remember { mutableStateOf(s.workDaysMask) }
    var llmCap by remember { mutableStateOf(s.llmDailyCap.toString()) }
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
    var warnOn by remember { mutableStateOf(s.warnEnabled) }
    var exclNames by remember { mutableStateOf(s.excludedNames) }
    var newExclName by remember { mutableStateOf("") }
    var crmOn by remember { mutableStateOf(s.crmEnabled) }
    var crmUrl by remember { mutableStateOf(s.crmBaseUrl) }
    var crmToken by remember { mutableStateOf(s.crmToken) }
    var crmAlways by remember { mutableStateOf(s.crmAlwaysAnswer) }
    var crmStatus by remember { mutableStateOf("") }
    var aboutSrc by remember { mutableStateOf(com.davnozdu.autoresponder.store.AboutInfo.source(ctx)) }
    var holOn by remember { mutableStateOf(s.holidaysEnabled) }
    var holSrc by remember { mutableStateOf(com.davnozdu.autoresponder.store.Holidays.source(ctx)) }
    var backupOn by remember { mutableStateOf(s.backupEnabled) }
    var backupKeep by remember { mutableStateOf(s.backupKeep.toString()) }
    var backupHour by remember { mutableStateOf(s.backupHour.toString()) }
    var backups by remember { mutableStateOf(com.davnozdu.autoresponder.store.Backup.list()) }
    val sims = remember { SimUtil.activeSims(ctx) }
    var defLang by remember { mutableStateOf(s.defaultLang) }

    var warnRu by remember { mutableStateOf(s.warnTemplateRaw("ru")) }
    var warnCs by remember { mutableStateOf(s.warnTemplateRaw("cs")) }
    var warnEn by remember { mutableStateOf(s.warnTemplateRaw("en")) }
    var tplRu by remember { mutableStateOf(s.template("ru")) }
    var tplCs by remember { mutableStateOf(s.template("cs")) }
    var tplEn by remember { mutableStateOf(s.template("en")) }

    var llmOn by remember { mutableStateOf(s.llmEnabled) }
    var llmThink by remember { mutableStateOf(s.llmThink) }
    var provider by remember { mutableStateOf(s.llmProvider) }
    var baseUrl by remember { mutableStateOf(s.llmBaseUrl) }
    var apiKey by remember { mutableStateOf(s.llmApiKey) }
    var model by remember { mutableStateOf(s.llmModel) }
    var models by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf("") }
    var exportKeys by remember { mutableStateOf(s.exportSecrets) }
    var updStatus by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<com.davnozdu.autoresponder.update.UpdateInfo?>(null) }
    var updBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - s.lastUpdateCheck > 86_400_000L) {
            val res = withContext(Dispatchers.IO) { com.davnozdu.autoresponder.update.Updater.check() }
            s.lastUpdateCheck = System.currentTimeMillis()
            if (res is com.davnozdu.autoresponder.update.UpdateCheck.Available) update = res.info
            updStatus = updLabel(res)
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
    // Импорт избранных (звёздных) контактов в список исключений мессенджеров.
    // Мессенджер показывает в уведомлении имя из телефонной книги — импортируем именно имена.
    fun importStarredContacts() {
        val names = com.davnozdu.autoresponder.rules.ContactUtil.starredNames(ctx)
        val added = s.addExcludedNames(names)
        exclNames = s.excludedNames
        Toast.makeText(ctx, when {
            names.isEmpty() -> "В телефонной книге нет избранных (звёздных) контактов"
            added == 0 -> "Все ${names.size} избранных уже в списке"
            else -> "Добавлено $added из ${names.size} избранных контактов"
        }, Toast.LENGTH_LONG).show()
    }
    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) importStarredContacts()
        else Toast.makeText(ctx, "Без доступа к контактам импорт невозможен", Toast.LENGTH_SHORT).show()
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(s.exportJson(exportKeys).toByteArray()) }
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
    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                com.davnozdu.autoresponder.store.AboutInfo.saveAppCopy(ctx, txt)
                aboutSrc = com.davnozdu.autoresponder.store.AboutInfo.source(ctx)
                Toast.makeText(ctx, "Файл «О компании» загружен", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(ctx, "Ошибка загрузки .md", Toast.LENGTH_SHORT).show() }
        }
    }
    val holLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                com.davnozdu.autoresponder.store.Holidays.saveAppCopy(ctx, txt)
                holSrc = com.davnozdu.autoresponder.store.Holidays.source(ctx)
                Toast.makeText(ctx, "Список праздников загружен", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(ctx, "Ошибка загрузки списка", Toast.LENGTH_SHORT).show() }
        }
    }
    val priceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                com.davnozdu.autoresponder.store.Prices.saveAppCopy(ctx, txt)
                priceSrc = com.davnozdu.autoresponder.store.Prices.source(ctx)
                priceCount = com.davnozdu.autoresponder.store.Prices.rows(ctx).size
                Toast.makeText(ctx, "Прайс загружен: $priceCount строк", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(ctx, "Ошибка загрузки прайса", Toast.LENGTH_SHORT).show() }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
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

            ExpandableSection("Версия приложения") {
                Text(com.davnozdu.autoresponder.update.Updater.currentVersion,
                    style = MaterialTheme.typography.headlineSmall)
                Text("сборка ${com.davnozdu.autoresponder.BuildConfig.VERSION_CODE}"
                    + (if (com.davnozdu.autoresponder.BuildConfig.DEBUG) " · debug" else ""),
                    style = MaterialTheme.typography.bodySmall)
                Text("Проверка последний раз: " +
                    (if (s.lastUpdateCheck > 0)
                        java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(s.lastUpdateCheck))
                     else "не выполнялась"),
                    style = MaterialTheme.typography.bodySmall)
                Text("Само приложение проверяет обновление раз в сутки при открытии настроек. "
                    + "Здесь можно проверить вручную в любой момент.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !updBusy, onClick = {
                        updStatus = "Проверяю…"
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                com.davnozdu.autoresponder.update.Updater.check()
                            }
                            s.lastUpdateCheck = System.currentTimeMillis()
                            update = (res as? com.davnozdu.autoresponder.update.UpdateCheck.Available)?.info
                            updStatus = updLabel(res)
                        }
                    }) { Text("Проверить обновления") }
                    // Кнопка установки нужна и здесь: баннер сверху легко пролистать мимо,
                    // а искать его обратно после проверки — лишний шаг.
                    update?.let { u ->
                        Button(enabled = !updBusy, onClick = {
                            updBusy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val f = com.davnozdu.autoresponder.update.Updater.download(ctx, u.apkUrl)
                                    f != null && com.davnozdu.autoresponder.update.Updater.install(ctx, f)
                                }
                                updBusy = false
                                Toast.makeText(ctx,
                                    if (ok) "Устанавливаю…" else "Ошибка обновления", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(if (updBusy) "Загрузка…" else "Обновить до ${u.version}") }
                    }
                }
                if (updStatus.isNotBlank())
                    Text(updStatus, style = MaterialTheme.typography.bodySmall)
                update?.let { u ->
                    if (u.notes.isNotBlank())
                        Text(u.notes, style = MaterialTheme.typography.bodySmall)
                }
            }

            ExpandableSection("Основное", initiallyOpen = true) {
                SwitchRow("Включён", enabled) { enabled = it; s.enabled = it }
                SwitchRow("Отвечать на звонки", respCalls) { respCalls = it; s.respondCalls = it }
                SwitchRow("Отвечать на SMS", respSms) { respSms = it; s.respondSms = it }
                SwitchRow("Уведомления (сводка, статус DND)", notifOn) {
                    notifOn = it; s.notificationsEnabled = it
                    if (!it) com.davnozdu.autoresponder.notif.AutoNotifications.cancelDnd(ctx)
                }
            }

            ExpandableSection("Уведомления о чёрном списке") {
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
            }

            ExpandableSection("Когда «закрыто» (расписание)") {
                SwitchRow("По системному режиму «Не беспокоить»", trigDnd) { trigDnd = it; s.triggerOnDnd = it }
                SwitchRow("По расписанию", trigSched) { trigSched = it; s.triggerOnSchedule = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(schedMode == 0, { schedMode = 0; s.scheduleMode = 0 }, { Text("Окно «закрыто»") })
                    FilterChip(schedMode == 1, { schedMode = 1; s.scheduleMode = 1 }, { Text("Рабочие часы/дни") })
                }
                if (schedMode == 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Работаем с")
                        OutlinedButton(onClick = { pickTime(ctx, workStart) { workStart = it; s.workStartMin = it } }) { Text(fmtMin(workStart)) }
                        Text("до")
                        OutlinedButton(onClick = { pickTime(ctx, workEnd) { workEnd = it; s.workEndMin = it } }) { Text(fmtMin(workEnd)) }
                    }
                    Text("Рабочие дни:", style = MaterialTheme.typography.labelMedium)
                    val days = listOf(2 to "Пн", 3 to "Вт", 4 to "Ср", 5 to "Чт", 6 to "Пт", 7 to "Сб", 1 to "Вс")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        days.forEach { (d, lbl) ->
                            FilterChip(selected = (workDays and (1 shl d)) != 0, onClick = {
                                workDays = workDays xor (1 shl d); s.workDaysMask = workDays
                            }, label = { Text(lbl) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (schedMode == 0) Text("Закрыто с")
                    OutlinedButton(onClick = {
                        pickTime(ctx, startMin) { startMin = it; s.scheduleStartMin = it }
                    }) { Text(fmtMin(startMin)) }
                    if (schedMode == 0) Text("до")
                    if (schedMode == 0) OutlinedButton(onClick = {
                        pickTime(ctx, endMin) { endMin = it; s.scheduleEndMin = it }
                    }) { Text(fmtMin(endMin)) }
                }
                Text(
                    if (startMin > endMin) "Активно ${fmtMin(startMin)} → ${fmtMin(endMin)} (через полночь)"
                    else "Активно ${fmtMin(startMin)} → ${fmtMin(endMin)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            ExpandableSection("Управление по SMS") {
                SwitchRow("Принимать команды с доверенных номеров", smsCmdOn) { smsCmdOn = it; s.smsCommandsOn = it }
                Text("Команды (регистр не важен): STATUS — состояние, OFF — выключить автоответ, "
                    + "ON — включить, PAUSE — пауза до следующего DND, DIGEST — показать сводку. "
                    + "Русские слова тоже понимаются: статус, выкл, вкл, пауза, сводка.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Отправителя SMS подделать несложно, поэтому по умолчанию выключено, "
                    + "а команды принимаются только с номеров из этого списка. Ответ уходит "
                    + "той же SMS на тот же номер.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newCmdNum, { newCmdNum = it }, label = { Text("Доверенный номер") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    Button(onClick = {
                        if (newCmdNum.isNotBlank()) {
                            s.addSmsCommandNumber(newCmdNum); smsCmdNums = s.smsCommandNumbers; newCmdNum = ""
                        }
                    }) { Text("+") }
                }
                CanonicalHint(newCmdNum)
                smsCmdNums.forEach { n ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(n)
                        TextButton(onClick = { s.removeSmsCommandNumber(n); smsCmdNums = s.smsCommandNumbers }) { Text("Удалить") }
                    }
                }
                if (smsCmdOn && smsCmdNums.isEmpty()) Text("Список пуст — ни одна команда не будет принята.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            ExpandableSection("Утренняя сводка") {
                SwitchRow("Присылать сводку за сутки", digestOn) {
                    digestOn = it; s.digestEnabled = it
                    com.davnozdu.autoresponder.notif.Digest.schedule(ctx)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("В")
                    OutlinedButton(onClick = {
                        pickTime(ctx, digestHour * 60) { m ->
                            digestHour = m / 60; s.digestHour = m / 60
                            com.davnozdu.autoresponder.notif.Digest.schedule(ctx)
                        }
                    }) { Text("%02d:00".format(digestHour)) }
                    OutlinedButton(onClick = {
                        com.davnozdu.autoresponder.notif.Digest.show(ctx)
                        Toast.makeText(ctx, "Сводка показана (если было что показать)", Toast.LENGTH_SHORT).show()
                    }) { Text("Показать сейчас") }
                }
                Text("Сколько было звонков и сообщений за сутки и кому вы ещё не ответили сами. "
                    + "Ночью не приходит — в этом и смысл.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { ctx.startActivity(Intent(ctx, InboxActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("📋 Требуют ответа") }
            }

            ExpandableSection("Тихий час (ночные SMS)") {
                SwitchRow("Не будить SMS ночью", quietOn) { quietOn = it; s.quietHoursOn = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("С")
                    OutlinedButton(onClick = { pickTime(ctx, quietStart) { quietStart = it; s.quietStartMin = it } }) { Text(fmtMin(quietStart)) }
                    Text("до")
                    OutlinedButton(onClick = { pickTime(ctx, quietEnd) { quietEnd = it; s.quietEndMin = it } }) { Text(fmtMin(quietEnd)) }
                }
                Text("Звонок ночью отклоняется как обычно, но ответная SMS уходит утром — "
                    + "и одна на номер, сколько бы раз ни звонили. Ошибся номером или звонит "
                    + "из другого часового пояса — не разбудим.",
                    style = MaterialTheme.typography.bodySmall)
                Text("На SMS и сообщения мессенджеров не влияет: человек написал сам, "
                    + "молчание в ответ выглядело бы поломкой.",
                    style = MaterialTheme.typography.bodySmall)
                val held = remember(quietOn) { com.davnozdu.autoresponder.store.HistoryDb.get(ctx).smsHoldCount() }
                if (held > 0) Text("Сейчас придержано: $held", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            ExpandableSection("Маска стран и выбор SIM") {
                Text("Номеру отвечаем, если он попал в список включённой карты. Отвечает та карта, "
                    + "чьё правило точнее (самый длинный подходящий префикс). Если правил нет — "
                    + "SIM по умолчанию из раздела «SIM и язык».",
                    style = MaterialTheme.typography.bodySmall)

                // Имя по системе, без номера слота — он уже в подписи тумблера.
                // Карты, которой нет в телефоне, сразу видно: правило для неё работать не будет.
                fun simHint(slot: Int): String =
                    sims.firstOrNull { it.slot == slot }?.name ?: "нет в телефоне"

                SwitchRow("SIM 1 — ${simHint(0)}", sim1On) { sim1On = it; s.sim1Enabled = it }
                if (sim1On) OutlinedTextField(prefixes1,
                    { prefixes1 = it; s.prefixesSim1 = it.split(",").map { p -> p.trim() } },
                    label = { Text("SIM 1 — префиксы, напр. +31") }, modifier = Modifier.fillMaxWidth())

                SwitchRow("SIM 2 — ${simHint(1)}", sim2On) { sim2On = it; s.sim2Enabled = it }
                if (sim2On) OutlinedTextField(prefixes2,
                    { prefixes2 = it; s.prefixesSim2 = it.split(",").map { p -> p.trim() } },
                    label = { Text("SIM 2 — префиксы, напр. +420") }, modifier = Modifier.fillMaxWidth())

                val conflicts = remember(prefixes1, prefixes2, sim1On, sim2On) {
                    if (sim1On && sim2On) s.conflictingPrefixes() else emptyList()
                }
                if (conflicts.isNotEmpty())
                    Text("Один префикс задан обеим картам: ${conflicts.joinToString(", ")}. "
                        + "Такие номера уйдут с SIM по умолчанию. Уберите повтор из одного списка "
                        + "или сделайте одно правило точнее (например +420 против +4).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)

                val activeEmpty = (!sim1On || prefixes1.isBlank()) && (!sim2On || prefixes2.isBlank())
                if (!sim1On && !sim2On)
                    Text("Обе карты выключены — автоответ не сработает.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                else if (activeEmpty)
                    Text("У включённых карт нет префиксов — автоответ не сработает ни для одного номера.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
            }

            ExpandableSection("Избранные (не отвечать)") {
                SwitchRow("Не отвечать звёздным контактам", exStarred) { exStarred = it; s.excludeStarred = it }
                SwitchRow("Не отвечать всем контактам из книги", exContacts) { exContacts = it; s.excludeContacts = it }
                Text("Оба переключателя действуют и на мессенджеры: там контакт ищется по имени "
                    + "из уведомления, потому что номера WhatsApp и Telegram не передают.",
                    style = MaterialTheme.typography.bodySmall)
                SwitchRow("Уважать приоритетных в «Не беспокоить»", respectDnd) { respectDnd = it; s.respectDndPriority = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newExcl, { newExcl = it }, label = { Text("Номер или маска") },
                        modifier = Modifier.weight(1f))
                    Button(onClick = {
                        if (newExcl.isNotBlank()) { s.addExcluded(newExcl); excluded = s.excludedNumbers; newExcl = "" }
                    }) { Text("+") }
                }
                CanonicalHint(newExcl)
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
                Text("Мессенджеры (WhatsApp, Telegram): имя из книги, номер или маска",
                    style = MaterialTheme.typography.titleSmall)
                Text("@username в уведомление НЕ попадает. Мессенджер подставляет имя, под "
                    + "которым человек записан в вашей телефонной книге («Пётр Новак»). "
                    + "Если контакта в книге нет: Telegram покажет имя из профиля собеседника, "
                    + "WhatsApp — номер в своём формате («+7 900 123-45-67»). Точную строку "
                    + "видно в журнале (from=…) и в истории переписки.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Маска: * — любой текст, ? — один символ. «Мама*» закроет «Мама Прага», "
                    + "«+420*» — все чешские номера.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newExclName, { newExclName = it },
                        label = { Text("Имя, номер или маска") },
                        modifier = Modifier.weight(1f))
                    Button(onClick = {
                        if (newExclName.isNotBlank()) { s.addExcludedName(newExclName); exclNames = s.excludedNames; newExclName = "" }
                    }) { Text("+") }
                }
                CanonicalHint(newExclName)
                OutlinedButton(onClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            ctx, Manifest.permission.READ_CONTACTS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) importStarredContacts()
                    else contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) { Text("Импортировать избранные контакты из книги") }
                exclNames.forEach { nm ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        // Показываем, КАК запись будет сопоставляться — иначе номер с припиской
                        // молча сравнивается как имя и правило не срабатывает.
                        val hint = com.davnozdu.autoresponder.rules.NameMatch.describe(nm)
                        Column(Modifier.weight(1f)) {
                            Text(nm)
                            Text(hint, style = MaterialTheme.typography.labelSmall,
                               color = if (hint.startsWith("сравнивается как ИМЯ"))
                                       MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { s.removeExcludedName(nm); exclNames = s.excludedNames }) { Text("Удалить") }
                    }
                }
            }

            ExpandableSection("CRM: статус заказа") {
                Text("Клиент спрашивает «когда будет готов заказ?» — приложение находит его "
                    + "в CRM по номеру телефона и отвечает само: этап заказа и последний "
                    + "статус с датой. Несколько заказов — спросит, про какой. Нужен живой "
                    + "мастер — клиент пишет ДА, и вопрос ложится в карточку заказа.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Номер берётся из SMS и звонка напрямую, а для WhatsApp и Telegram — "
                    + "из телефонной книги по имени отправителя. Нет контакта в книге — "
                    + "в CRM не идём.",
                    style = MaterialTheme.typography.bodySmall)
                SwitchRow("Включить", crmOn) { crmOn = it; s.crmEnabled = it }
                OutlinedTextField(crmUrl, { crmUrl = it; s.crmBaseUrl = it },
                    label = { Text("Адрес CRM (https://…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(crmToken, { crmToken = it; s.crmToken = it },
                    label = { Text("Токен") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Токен выпускается в CRM: Настройки → Автоответчик. Показывается один раз.",
                    style = MaterialTheme.typography.bodySmall)
                SwitchRow("Отвечать и в рабочее время", crmAlways) { crmAlways = it; s.crmAlwaysAnswer = it }
                Text("Обычный автоответ уходит только когда «закрыто». Статус заказа — не "
                    + "«мы закрыты», и спрашивают о нём чаще всего днём. При включённом "
                    + "переключателе днём отвечаем только известному клиенту и только на "
                    + "вопрос о заказе.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        crmStatus = "Проверяю…"
                        scope.launch {
                            // Причина отказа почти всегда есть в ответе — показываем её,
                            // а не перечисляем всё, что могло пойти не так.
                            val err = withContext(Dispatchers.IO) {
                                com.davnozdu.autoresponder.crm.CrmRoster.check(ctx)
                            }
                            crmStatus = err
                                ?: ("Связь есть. В реестре "
                                    + com.davnozdu.autoresponder.crm.CrmRoster.size(ctx)
                                    + " номеров с активными записями.")
                        }
                    }) { Text("Проверить связь") }
                    OutlinedButton(onClick = {
                        com.davnozdu.autoresponder.crm.CrmRoster.clear(ctx)
                        com.davnozdu.autoresponder.crm.CrmFlow.invalidate()
                        crmStatus = "Реестр очищен."
                    }) { Text("Сбросить реестр") }
                }
                if (crmStatus.isNotBlank())
                    Text(crmStatus, style = MaterialTheme.typography.bodySmall)
            }

            ExpandableSection("Лимиты и предупреждение") {
                SwitchRow("Предупреждение перед тишиной (7-е сообщение)", warnOn) { warnOn = it; s.warnEnabled = it }
                Text("После лимита ответов отправить одно системное сообщение: отвечает AI, человек ответит в рабочее время / через N ч. Дальше — тишина до таймаута.",
                    style = MaterialTheme.typography.bodySmall)
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
            }

            ExpandableSection("SIM и язык") {
                Text("SIM по умолчанию — для номеров, не попавших ни в одно правило выше.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = smsSlot == 0, enabled = sim1On,
                        onClick = { smsSlot = 0; s.smsSlot = 0 }, label = { Text("SIM 1") })
                    FilterChip(selected = smsSlot == 1, enabled = sim2On,
                        onClick = { smsSlot = 1; s.smsSlot = 1 }, label = { Text("SIM 2") })
                }
                if ((smsSlot == 0 && !sim1On) || (smsSlot == 1 && !sim2On))
                    Text("Карта по умолчанию выключена выше — ответы уйдут с включённой.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                if (sims.isEmpty()) Text("SIM не определены (нужно READ_PHONE_STATE)",
                    style = MaterialTheme.typography.bodySmall)
                else sims.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(defLang, { defLang = it.trim(); s.defaultLang = it.trim() },
                    label = { Text("Язык по умолчанию (en/ru/cs)") }, modifier = Modifier.fillMaxWidth())
            }

            ExpandableSection("Шаблоны — ответ БЕЗ LLM (заглушка)") {
                Text("Этот текст уходит, когда LLM не отработала: выключена, нет интернета, не задана "
                    + "модель/ключ, ошибка или пустой ответ. Промпты тут ни при чём — причина всегда "
                    + "пишется в Журнал строкой «Ответ шаблоном».",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(tplRu, { tplRu = it; s.setTemplate("ru", it) }, label = { Text("RU") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tplCs, { tplCs = it; s.setTemplate("cs", it) }, label = { Text("CS") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tplEn, { tplEn = it; s.setTemplate("en", it) }, label = { Text("EN") }, modifier = Modifier.fillMaxWidth())
                Text("Предупреждение перед тишиной (тоже без LLM). {hours} — часы таймаута.",
                    style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(warnRu, { warnRu = it; s.setWarnTemplate("ru", it) }, label = { Text("RU (предупреждение)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(warnCs, { warnCs = it; s.setWarnTemplate("cs", it) }, label = { Text("CS (предупреждение)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(warnEn, { warnEn = it; s.setWarnTemplate("en", it) }, label = { Text("EN (предупреждение)") }, modifier = Modifier.fillMaxWidth())
            }

            ExpandableSection("LLM — основная модель") {
                SwitchRow("Использовать LLM", llmOn) { llmOn = it; s.llmEnabled = it }
                SwitchRow("Режим размышления (reasoning)", llmThink) { llmThink = it; s.llmThink = it }
                Text(if (llmThink)
                    "Вкл: модель думает (большой бюджет токенов, таймаут до 95с), ответ обрезается под лимит SMS. Для reasoning-моделей (deepseek и т.п.)."
                    else "Выкл: прямой краткий ответ, быстрый фолбэк ~13с. Подходит всем моделям (рекомендуется, напр. gemma).",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ollama", "openai", "gemini", "deepseek").forEach { p ->
                        FilterChip(selected = provider == p, onClick = {
                            if (provider != p) {
                                provider = p; s.llmProvider = p
                                baseUrl = LlmFactory.defaultBaseUrl(p); s.llmBaseUrl = baseUrl
                                // Модель принадлежит провайдеру: имя от прежнего (например
                                // gemma4:31b из Ollama) на новом API не существует, запрос падает
                                // и ответ молча уходит офлайн-шаблоном. Чистим выбор.
                                model = ""; s.llmModel = ""; models = emptyList()
                                status = "Провайдер изменён — выберите модель заново"
                            }
                        }, label = { Text(if (p == "deepseek") "DSeek" else p) })
                    }
                }
                OutlinedTextField(baseUrl, { baseUrl = it; s.llmBaseUrl = it },
                    label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it; s.llmApiKey = it },
                    label = { Text("API key (для облака)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it; s.llmModel = it },
                    label = { Text("Модель") }, modifier = Modifier.fillMaxWidth())
                // Частая ошибка: имя модели из Ollama («gemma3:27b») оставлено при облачном
                // провайдере — запрос падает, и ответ уходит офлайн-шаблоном.
                if (model.isBlank())
                    Text("Модель не выбрана — этот канал работать не будет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                else if (provider != "ollama" && model.contains(":"))
                    Text("«$model» выглядит как имя модели Ollama, а провайдер — $provider. "
                        + "Нажмите «Запросить все модели» и выберите из списка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                OutlinedTextField(llmCap, { llmCap = it; it.toIntOrNull()?.let { v -> s.llmDailyCap = v } },
                    label = { Text("Лимит LLM в день (0 = без лимита)") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
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
            }

            ExpandableSection("LLM — резервная модель") {
                SwitchRow("Использовать резервную", llm2On) { llm2On = it; s.llm2Enabled = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ollama", "openai", "gemini", "deepseek").forEach { p ->
                        FilterChip(selected = provider2 == p, onClick = {
                            if (provider2 != p) {
                                provider2 = p; s.llm2Provider = p
                                baseUrl2 = LlmFactory.defaultBaseUrl(p); s.llm2BaseUrl = baseUrl2
                                model2 = ""; s.llm2Model = ""; models2 = emptyList()
                                status2 = "Провайдер изменён — выберите модель заново"
                            }
                        }, label = { Text(if (p == "deepseek") "DSeek" else p) })
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
            }

            ExpandableSection("Промпты AI") {
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
            }

            ExpandableSection("Файл «О компании» (.md)") {
                Text(when {
                    aboutSrc == null -> "Не задан. Приоритет: загруженный .md → /sdcard/AutoResponder/about.md → «Факты о компании»."
                    aboutSrc!!.contains("/files/") -> "Активен загруженный файл (в приложении). Он важнее полей выше."
                    else -> "Активен файл: $aboutSrc"
                }, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { mdLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*")) }) { Text("Загрузить .md") }
                    OutlinedButton(onClick = {
                        com.davnozdu.autoresponder.store.AboutInfo.saveAppCopy(ctx, null)
                        aboutSrc = com.davnozdu.autoresponder.store.AboutInfo.source(ctx)
                        Toast.makeText(ctx, "Загруженный файл удалён", Toast.LENGTH_SHORT).show()
                    }) { Text("Убрать загруженный") }
                }
            }

            ExpandableSection("Прайс-лист (цены как факты)") {
                Text("Цену модель НЕ придумывает: код подставляет в промпт строки прайса, "
                    + "а нет подходящей — запрещает называть цифру и велит сказать, что "
                    + "мастер уточнит после диагностики.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Формат CSV, одна услуга в строке: устройство;услуга;цена;срок. "
                    + "«#» — комментарий. Пример: iPhone 12;замена экрана;3500 Kč;1 день",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { priceLauncher.launch(arrayOf("*/*")) }) { Text("Загрузить .csv") }
                    if (priceSrc != null) OutlinedButton(onClick = {
                        com.davnozdu.autoresponder.store.Prices.saveAppCopy(ctx, null)
                        priceSrc = com.davnozdu.autoresponder.store.Prices.source(ctx)
                        priceCount = com.davnozdu.autoresponder.store.Prices.rows(ctx).size
                    }) { Text("Убрать копию") }
                }
                Text(priceSrc?.let { "Источник: $it ($priceCount строк)" }
                    ?: "Прайс не задан — цены в ответах не называются. "
                       + "Можно положить файл в ${com.davnozdu.autoresponder.store.Prices.PUBLIC_PATH}",
                    style = MaterialTheme.typography.bodySmall)
            }

            ExpandableSection("Праздники (гос. выходные)") {
                SwitchRow("Учитывать праздники", holOn) { holOn = it; s.holidaysEnabled = it }
                Text(when {
                    !holOn -> "Выключено. Включите и загрузите список — LLM будет знать, когда офис закрыт (только по договорённости)."
                    holSrc == null -> "Включено, но список не загружен. Загрузите .txt или положите /sdcard/AutoResponder/holidays.txt"
                    holSrc!!.contains("/files/") -> "Активен загруженный список (в приложении)."
                    else -> "Активен файл: $holSrc"
                }, style = MaterialTheme.typography.bodySmall)
                Text("Формат: одна дата в строке. MM-DD — ежегодно (01-01 Новый год); YYYY-MM-DD — конкретная дата (2026-04-06 Пасха). # — комментарий.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { holLauncher.launch(arrayOf("text/plain", "*/*")) }) { Text("Загрузить список") }
                    OutlinedButton(onClick = {
                        com.davnozdu.autoresponder.store.Holidays.saveAppCopy(ctx, null)
                        holSrc = com.davnozdu.autoresponder.store.Holidays.source(ctx)
                        Toast.makeText(ctx, "Загруженный список удалён", Toast.LENGTH_SHORT).show()
                    }) { Text("Убрать загруженный") }
                }
            }

            ExpandableSection("Планировщик: ежедневный бэкап") {
                Text("Копия базы истории (контекст для LLM) в /sdcard/AutoResponder/backups с ротацией.",
                    style = MaterialTheme.typography.bodySmall)
                SwitchRow("Ежедневный бэкап", backupOn) {
                    backupOn = it; s.backupEnabled = it
                    if (it) com.davnozdu.autoresponder.store.Backup.schedule(ctx)
                    else com.davnozdu.autoresponder.store.Backup.cancel(ctx)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(backupKeep, { backupKeep = it; it.toIntOrNull()?.let { v -> s.backupKeep = v } },
                        label = { Text("Хранить копий") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(backupHour, { backupHour = it; it.toIntOrNull()?.let { v -> s.backupHour = v.coerceIn(0,23); com.davnozdu.autoresponder.store.Backup.schedule(ctx) } },
                        label = { Text("Час (0-23)") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Button(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { com.davnozdu.autoresponder.store.Backup.run(ctx) }
                        backups = com.davnozdu.autoresponder.store.Backup.list()
                        Toast.makeText(ctx, if (f != null) "Бэкап сохранён: ${f.name}" else "Ошибка бэкапа (нужен доступ к файлам)", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Сделать бэкап сейчас") }
                if (backups.isEmpty()) Text("Копий пока нет", style = MaterialTheme.typography.bodySmall)
                backups.take(12).forEach { bk ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${bk.name} (${bk.length()/1024} КБ)", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { com.davnozdu.autoresponder.store.Backup.restore(ctx, bk) }
                                Toast.makeText(ctx, if (ok) "Восстановлено ✓" else "Ошибка восстановления", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Восстановить") }
                    }
                }
            }

            ExpandableSection("Разрешения и роли") {
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
            }

            ExpandableSection("Экраны") {
                Button(onClick = { ctx.startActivity(Intent(ctx, StatusActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("✅ Состояние (проверка готовности)") }
                Button(onClick = { ctx.startActivity(Intent(ctx, InboxActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("📋 Требуют ответа") }
                Button(onClick = { ctx.startActivity(Intent(ctx, HistoryActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("💬 История общения") }
                Button(onClick = { ctx.startActivity(Intent(ctx, HistoryChatActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("🤖 История запросов (чат с AI)") }
                Button(onClick = { ctx.startActivity(Intent(ctx, BlacklistActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("🚫 Чёрный список") }
                Button(onClick = { ctx.startActivity(Intent(ctx, AppPickerActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("📱 Приложения для автоответа") }
            }

            ExpandableSection("Импорт / экспорт настроек") {
                SwitchRow("Выгружать API-ключи LLM", exportKeys) { exportKeys = it; s.exportSecrets = it }
                Text(if (exportKeys)
                    "Ключи попадут в буфер обмена / файл в открытом виде — включайте только для переноса на своё устройство."
                    else "Ключи LLM не выгружаются (безопасно). На новом устройстве введите их вручную.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("autoresp", s.exportJson(exportKeys)))
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
            }

            ExpandableSection("Журнал") {
                var logText by remember { mutableStateOf(EventLog(ctx).all()) }
                var logFileOn by remember { mutableStateOf(s.logToFile) }
                var logSize by remember { mutableStateOf(com.davnozdu.autoresponder.data.LogFile.sizeBytes()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { logText = EventLog(ctx).all() }) { Text("Обновить") }
                    OutlinedButton(onClick = { EventLog(ctx).clear(); logText = "" }) { Text("Очистить") }
                }
                SwitchRow("Писать журнал в файл", logFileOn) { logFileOn = it; s.logToFile = it }
                Text("В памяти журнал живёт до перезагрузки. Файл нужен, когда разбираться "
                    + "приходится через сутки: «почему клиенту не ответили вчера вечером».",
                    style = MaterialTheme.typography.bodySmall)
                Text("${com.davnozdu.autoresponder.data.LogFile.DIR} — по файлу на сутки, "
                    + "хранится ${s.logKeepDays} дней, сейчас ${logSize / 1024} КБ",
                    style = MaterialTheme.typography.bodySmall)
                if (logSize > 0) OutlinedButton(onClick = {
                    com.davnozdu.autoresponder.data.LogFile.clear(); logSize = 0
                }) { Text("Удалить файлы журнала") }
                Text(logText.ifBlank { "пусто" }, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (open) "▲" else "▼")
            }
            if (open) content()
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

/** Человеческий текст результата проверки обновления (ошибку не выдаём за «актуально»). */
private fun updLabel(res: com.davnozdu.autoresponder.update.UpdateCheck): String {
    val cur = com.davnozdu.autoresponder.update.Updater.currentVersion
    return when (res) {
        is com.davnozdu.autoresponder.update.UpdateCheck.Available ->
            "Доступна ${res.info.version} (установлена $cur)"
        is com.davnozdu.autoresponder.update.UpdateCheck.UpToDate ->
            "Установлена последняя версия ✓ ($cur, в релизах ${res.latest})"
        is com.davnozdu.autoresponder.update.UpdateCheck.Failed ->
            "Не удалось проверить: ${res.reason}"
    }
}

/** Показывает, в каком виде номер ляжет в список: с «+» и без разделителей. */
@Composable
private fun CanonicalHint(raw: String) {
    val canon = com.davnozdu.autoresponder.rules.PhoneMask.canonical(raw)
    if (canon != raw.trim()) Text("Будет сохранено: $canon",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary)
}

private fun fmtMin(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun pickTime(ctx: Context, minutes: Int, onSet: (Int) -> Unit) {
    val h = minutes / 60; val m = minutes % 60
    android.app.TimePickerDialog(ctx, { _, hh, mm -> onSet(hh * 60 + mm) }, h, m, true).show()
}

