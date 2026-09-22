package com.davnozdu.autoresponder.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.davnozdu.autoresponder.notif.CallerOverlay

/**
 * Звонок закончился — снять карточку поверх экрана.
 *
 * Это один из трёх независимых способов её убрать (ещё крестик и таймаут): залипшее
 * окно поверх всего — худшее, что может случиться с этой функцией.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_IDLE) {
            CallerOverlay.hide(context.applicationContext)
        }
    }
}
