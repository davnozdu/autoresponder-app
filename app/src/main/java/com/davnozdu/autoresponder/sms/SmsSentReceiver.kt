package com.davnozdu.autoresponder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.respond.Outgoing

/** Per-segment acknowledgements, no whole-message retries after partial transmission. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf("com.davnozdu.autoresponder.SMS_SENT","com.davnozdu.autoresponder.SMS_DELIVERED")) return
        val id=intent.getStringExtra("outgoing") ?: return
        val code=resultCode
        val pending=goAsync()
        Thread {
            try { Outgoing.acknowledgement(context.applicationContext,id,intent.getIntExtra("part",0),code,intent.getBooleanExtra("delivery",false)) }
            finally { pending.finish() }
        }.start()
    }
}
