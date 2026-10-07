package com.davnozdu.autoresponder.msgrec

/** Priority order of the existing voice-call rules; DND explicitly uses voicemail. */
internal object MsgrCallPolicy {
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
