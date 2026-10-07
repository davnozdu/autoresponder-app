package com.davnozdu.autoresponder.msgrec

/** Priority order of the existing voice-call rules; DND explicitly uses voicemail. */
internal object MsgrCallPolicy {
    const val ANSWER_DELAY_MS = 4000L // Approximate two caller-side rings; messenger exposes no ring count.
    fun remainingAnswerDelay(receivedAt: Long, now: Long): Long =
        (ANSWER_DELAY_MS - (now - receivedAt)).coerceIn(0, ANSWER_DELAY_MS)
    enum class Route { NONE, SCREENING, VOICEMAIL, VACATION, BLACKLIST, HEADSET }

    fun route(enabled: Boolean, paused: Boolean, alreadyInCall: Boolean,
              blacklistAllowsCalls: Boolean?, skip: Boolean, vacation: Boolean,
              dnd: Boolean, screening: Boolean, headset: Boolean,
              closed: Boolean, closedVoice: Boolean): Route = when {
        !enabled || paused || alreadyInCall -> Route.NONE
        skip -> Route.NONE
        blacklistAllowsCalls != null -> if (blacklistAllowsCalls) Route.NONE else Route.BLACKLIST
        vacation -> Route.VACATION
        dnd -> Route.VOICEMAIL
        screening -> Route.SCREENING
        headset -> Route.HEADSET
        closed && closedVoice -> Route.VOICEMAIL
        else -> Route.NONE
    }
}
