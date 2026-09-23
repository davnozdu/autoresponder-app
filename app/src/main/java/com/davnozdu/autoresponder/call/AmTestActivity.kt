package com.davnozdu.autoresponder.call

import android.app.Activity
import android.os.Bundle

/**
 * Невидимый триггер теста блокировки экрана/тача без реального звонка:
 *   adb shell am start -n com.davnozdu.autoresponder/.call.AmTestActivity --ei sec 25
 *
 * `am broadcast` к этому пакету попадает под defer-политику OxygenOS для фоновых/замороженных
 * приложений (BroadcastQueue setDeferPolicy ... defer: true) и может не доставиться вовсе.
 * `am start` активности отрабатывает мгновенно (так система будит приложение под реальный
 * входящий звонок) — тот же путь используем и здесь.
 */
class AmTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sec = intent?.getIntExtra("sec", 20) ?: 20
        AnswerMachineService.startTest(this, sec)
        finish()
    }
}
