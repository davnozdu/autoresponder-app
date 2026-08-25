package com.davnozdu.autoresponder.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.respond.Kind
import com.davnozdu.autoresponder.respond.Responder

/** DEBUG-only. Триггер конвейера ответа через adb broadcast.
 *  am broadcast -a com.davnozdu.autoresponder.TEST --es num "+420601112233" \
 *     --es text "Ahoj, jste otevřeno?" --es kind SMS --ez send false
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.davnozdu.autoresponder.TEST") return
        if (intent.getStringExtra("kind") == "STATUS") { Responder.debugStatus(context); return }
        if (intent.getStringExtra("kind") == "MODELS") { Responder.debugModels(context); return }
        val num = intent.getStringExtra("num")
        val text = intent.getStringExtra("text")
        val kind = if (intent.getStringExtra("kind") == "CALL") Kind.CALL else Kind.SMS
        val send = intent.getBooleanExtra("send", false)
        Responder.debugCompose(context, num, text, kind, send)
    }
}
