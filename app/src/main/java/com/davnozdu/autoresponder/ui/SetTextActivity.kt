package com.davnozdu.autoresponder.ui

import android.app.Activity
import android.os.Bundle
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings

/**
 * Переключение произвольных строковых настроек в обход UI — тот же приём и мотивация, что и у
 * [SetFlagActivity] (`am start`, не `am broadcast`), тот же маленький явный whitelist. Нужен
 * отдельно от [SetFlagActivity], потому что там `--ez` (boolean), здесь `--es` (строка) — в
 * первую очередь для API-ключей: поставить секрет прямо в SharedPreferences на устройстве,
 * не проходя через git/буфер обмена/скриншоты.
 *
 *   adb shell am start -n com.davnozdu.autoresponder/.ui.SetTextActivity \
 *     --es key tr_api_key --es value gsk_...
 *
 * Ключи: tr_api_key (Settings.transcribeApiKey), tr_model (Settings.transcribeModel).
 */
class SetTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = EventLog(this)
        val key = intent?.getStringExtra("key") ?: ""
        val value = intent?.getStringExtra("value") ?: ""
        val s = Settings(this)
        val ok = when (key) {
            "tr_api_key" -> { s.transcribeApiKey = value; true }
            "tr_model" -> { s.transcribeModel = value; true }
            else -> false
        }
        // Значение НЕ пишем в лог целиком — секрет; только сам факт и длину, для диагностики.
        log.add(if (ok) "SETTEXT: $key = <${value.length} симв.>" else "SETTEXT: неизвестный ключ '$key'")
        finish()
    }
}
