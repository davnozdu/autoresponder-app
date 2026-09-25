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
 * Ключи: enabled, screen_enabled, respond_sms, respond_calls.
 */
class SetFlagActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = EventLog(this)
        val key = intent?.getStringExtra("key") ?: ""
        val value = intent?.getBooleanExtra("value", false) ?: false
        val s = Settings(this)
        val ok = when (key) {
            "enabled" -> { s.enabled = value; true }
            "screen_enabled" -> { s.screeningEnabled = value; true }
            "respond_sms" -> { s.respondSms = value; true }
            "respond_calls" -> { s.respondCalls = value; true }
            else -> false
        }
        log.add(if (ok) "SETFLAG: $key = $value" else "SETFLAG: неизвестный ключ '$key'")
        finish()
    }
}
