package com.davnozdu.autoresponder.ui

import android.app.Activity
import android.os.Bundle
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings

/**
 * Переключение частых булевых настроек в обход UI — та же мотивация и приём (`am start`,
 * не `am broadcast`, см. [ImportAudioActivity]), маленький явный whitelist вместо рефлексии
 * по Settings: опечатка в ключе просто пишется в лог, а не падает и не трогает что-то не то.
 *
 *   adb shell am start -n com.davnozdu.autoresponder/.ui.SetFlagActivity \
 *     --es key screen_enabled --ez value true
 *
 * Ключи: enabled, screen_enabled, respond_sms, respond_calls, vacation_mode.
 */
class SetFlagActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = EventLog(this)
        val key = intent?.getStringExtra("key") ?: ""
        val value = intent?.getBooleanExtra("value", false) ?: false
        // Signature-protected ADB hook: reproduce a background activity PendingIntent from
        // an ordinary app UID, with creator privileges denied. Root must supply its own BAL
        // permission. No calls, messages, recordings, or settings are changed by this probe.
        if (key == "msgr_dispatch_probe") {
            val channel = "msgr_dispatch_probe"
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel(channel,
                "Проверка автоответа", android.app.NotificationManager.IMPORTANCE_LOW))
            val id = android.os.SystemClock.elapsedRealtime()
            val options = android.app.ActivityOptions.makeBasic()
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                options.setPendingIntentCreatorBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)
            }
            val action = android.app.PendingIntent.getActivity(this, 5176,
                android.content.Intent(this, SetFlagActivity::class.java)
                    .putExtra("key", "__probe_noop__").putExtra("probe_id", id),
                android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                options.toBundle())
            nm.notify("autoresp-msgr-activity-probe", 5176,
                android.app.Notification.Builder(this, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Проверка автоответа").setContentText("Локальный тест")
                    .setContentIntent(action).build())
            log.add("MSGR ACTIVITY PROBE: подготовлено id=$id")
            finish(); return
        }
        if (key == "__probe_noop__") {
            log.add("MSGR ACTIVITY PROBE: доставлено id=${intent.getLongExtra("probe_id", -1)}")
            getSystemService(android.app.NotificationManager::class.java)
                .cancel("autoresp-msgr-activity-probe", 5176)
            finish(); return
        }
        val s = Settings(this)
        val ok = when (key) {
            "enabled" -> { s.enabled = value; true }
            "screen_enabled" -> { s.screeningEnabled = value; true }
            "respond_sms" -> { s.respondSms = value; true }
            "respond_calls" -> { s.respondCalls = value; true }
            "vacation_mode" -> { s.vacationModeEnabled = value; true }
            "msgr_am_enabled" -> {
                s.msgrAmEnabled = value
                if (!value) com.davnozdu.autoresponder.msgrec.MsgrAnswerManager.cancel()
                true
            }
            else -> false
        }
        log.add(if (ok) "SETFLAG: $key = $value" else "SETFLAG: неизвестный ключ '$key'")
        finish()
    }
}
