package com.davnozdu.autoresponder.ui

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

class StatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { StatusScreen() } }
    }
}

private data class Check(val name: String, val ok: Boolean, val fix: (() -> Unit)?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    val checks = remember(refresh) { buildChecks(ctx) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Состояние") },
            actions = { TextButton(onClick = { refresh++ }) { Text("Обновить") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val allOk = checks.all { it.ok }
            Text(if (allOk) "Всё готово ✓" else "Есть проблемы",
                style = MaterialTheme.typography.titleLarge,
                color = if (allOk) Color(0xFF2E7D32) else Color(0xFFC62828))
            checks.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (c.ok) "✓" else "✗",
                        color = if (c.ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 10.dp))
                    Text(c.name, Modifier.weight(1f))
                    if (!c.ok && c.fix != null) TextButton(onClick = { c.fix.invoke(); refresh++ }) { Text("Исправить") }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun buildChecks(ctx: Context): List<Check> {
    val list = ArrayList<Check>()
    val rm = ctx.getSystemService(RoleManager::class.java)
    list.add(Check("Роль скрининга звонков",
        rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true) {
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING))
            ctx.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    })
    list.add(Check("Доступ к уведомлениям (мессенджеры)",
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    })
    val nm = ctx.getSystemService(NotificationManager::class.java)
    list.add(Check("Доступ к режиму «Не беспокоить»",
        nm?.isNotificationPolicyAccessGranted == true) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    })
    val pm = ctx.getSystemService(PowerManager::class.java)
    list.add(Check("Оптимизация батареи отключена",
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + ctx.packageName)))
    })
    val perms = listOf(
        "SMS приём" to Manifest.permission.RECEIVE_SMS,
        "SMS отправка" to Manifest.permission.SEND_SMS,
        "Телефон" to Manifest.permission.READ_PHONE_STATE,
        "Контакты" to Manifest.permission.READ_CONTACTS,
        "Журнал звонков" to Manifest.permission.WRITE_CALL_LOG,
        "Уведомления" to Manifest.permission.POST_NOTIFICATIONS)
    for ((label, p) in perms) {
        list.add(Check("Право: $label",
            ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED, null))
    }
    return list
}
