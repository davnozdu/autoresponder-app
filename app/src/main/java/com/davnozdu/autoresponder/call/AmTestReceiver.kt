package com.davnozdu.autoresponder.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Тестовый ресивер: проверить блокировку экрана+тача БЕЗ реального звонка — чтобы подбирать
 * поведение, не гоняя живые тестовые вызовы каждый раз.
 *
 *   adb shell am broadcast -a com.davnozdu.autoresponder.TEST_BLOCK_ON --ei sec 20
 *
 * Идёт через [AnswerMachineService.startTest] (тот же foreground-сервис, что и в реальном
 * звонке) — иначе процесс глушится OxygenOS-фризером на первом же LcdOff, как обычное
 * фоновое приложение, и тест ничего не проверяет.
 */
class AmTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ON) return
        val sec = intent.getIntExtra("sec", 20)
        AnswerMachineService.startTest(context, sec)
    }

    companion object {
        const val ACTION_ON = "com.davnozdu.autoresponder.TEST_BLOCK_ON"
    }
}
