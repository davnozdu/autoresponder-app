package com.davnozdu.autoresponder.call

import android.telecom.Call
import android.telecom.CallScreeningService
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.Kind
import com.davnozdu.autoresponder.respond.Responder
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.rules.SkipPolicy
import com.davnozdu.autoresponder.store.HistoryLogger

/**
 * Screening всех входящих звонков (держим роль CALL_SCREENING).
 * Когда «закрыто» и номер подходит под маску — отклоняем и шлём авто-SMS.
 */
class CallScreeningServiceImpl : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondAllow(callDetails); return
        }

        val number = callDetails.handle?.schemeSpecificPart // tel:+420... -> +420...
        if (number != null) HistoryLogger.record(this, number, "call", "in", "входящий звонок")
        val s = Settings(this)
        val closedReason = ClosedState.reason(this, s)
        val matches = PhoneMask.matches(number, s.allowedPrefixes)
        val skip = SkipPolicy.reason(this, number, s, isCall = true) != null

        if (s.enabled && s.respondCalls && closedReason != null && matches && !skip) {
            // Отклоняем звонок без записи в журнал пропущенных/уведомления.
            val response = CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
            respondToCall(callDetails, response)
            EventLog(this).add("CALL ${number ?: "?"} — отклонён (закрыто), SMS через ${s.replyDelayMs} мс")
            Responder.handle(this, number, null, Kind.CALL)
        } else {
            respondAllow(callDetails)
        }
    }

    private fun respondAllow(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
