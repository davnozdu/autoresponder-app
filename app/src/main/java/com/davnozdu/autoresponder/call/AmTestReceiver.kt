package com.davnozdu.autoresponder.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.EventLog

/**
 * Тестовый ресивер: включить/выключить блокировку экрана+тача БЕЗ реального звонка —
 * чтобы проверять/подбирать поведение, не гоняя живые тестовые вызовы каждый раз.
 *
 *   adb shell am broadcast -a com.davnozdu.autoresponder.TEST_BLOCK_ON
 *   adb shell am broadcast -a com.davnozdu.autoresponder.TEST_BLOCK_OFF
 *
 * Мьют/громкость сюда намеренно не входят — вне звонка STREAM_VOICE_CALL не значит ничего.
 */
class AmTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_ON -> {
                EventLog(app).add("AM ТЕСТ: включаю блокировку (оверлей + screenoff)")
                AmBlockOverlay.show(app)
                AmBridge.screenOff(app)
            }
            ACTION_OFF -> {
                EventLog(app).add("AM ТЕСТ: снимаю блокировку")
                AmBlockOverlay.hide(app)
            }
        }
    }

    companion object {
        const val ACTION_ON = "com.davnozdu.autoresponder.TEST_BLOCK_ON"
        const val ACTION_OFF = "com.davnozdu.autoresponder.TEST_BLOCK_OFF"
    }
}
