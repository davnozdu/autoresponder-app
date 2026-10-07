package com.davnozdu.autoresponder.msgrec

import android.app.Notification
import android.app.PendingIntent

/** Shared validation for the app listener and the privileged answer dispatcher. */
internal object IncomingCallNotification {
    val packages = setOf("org.telegram.messenger", "com.whatsapp", "com.whatsapp.w4b")

    fun answer(n: Notification): PendingIntent? {
        val fields = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
            .joinToString(" ") { n.extras.getCharSequence(it)?.toString().orEmpty() }
        val video = n.extras.getBoolean("android.callIsVideo", false)
        if (IncomingCallPolicy.isVideo(video, fields)) return null
        val callType = n.extras.getInt("android.callType", 0)
        if (callType == 1) {
            @Suppress("DEPRECATION")
            return n.extras.getParcelable<PendingIntent>("android.answerIntent")
        }
        val actions = n.actions ?: return null
        val index = IncomingCallPolicy.legacyAnswerIndex(n.category == Notification.CATEGORY_CALL,
            callType, video, fields, actions.map { it.title?.toString().orEmpty() }) ?: return null
        return actions[index].actionIntent
    }
}
