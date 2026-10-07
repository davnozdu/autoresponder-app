package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.msgrec.MsgrCallPolicy
import com.davnozdu.autoresponder.msgrec.MsgrCallPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class MsgrCallPolicyTest {
    private fun route(enabled: Boolean = true, paused: Boolean = false, busy: Boolean = false,
                      blacklist: Boolean? = null, skip: Boolean = false, vacation: Boolean = false,
                      dnd: Boolean = false, screening: Boolean = false, headset: Boolean = false,
                      closed: Boolean = false, voice: Boolean = true) =
        MsgrCallPolicy.route(enabled, paused, busy, blacklist, skip, vacation, dnd,
            screening, headset, closed, voice)

    @Test fun dndUsesVoicemailEvenDuringWorkAndWhenClosedModeIsSms() {
        assertEquals(Route.VOICEMAIL, route(dnd = true, screening = true, voice = false))
        assertEquals(Route.VOICEMAIL, route(dnd = true, headset = true))
        assertEquals(Route.VACATION, route(dnd = true, vacation = true))
    }
    @Test fun workScreeningWinsOverHeadsetAndClosedHours() {
        assertEquals(Route.SCREENING, route(screening = true, headset = true, closed = true))
        assertEquals(Route.NONE, route())
        assertEquals(Route.VOICEMAIL, route(closed = true))
        assertEquals(Route.NONE, route(closed = true, voice = false))
    }
    @Test fun offPauseExistingCallAndExceptionsPreventAutomaticAnswer() {
        assertEquals(Route.NONE, route(enabled = false, dnd = true))
        assertEquals(Route.NONE, route(paused = true, screening = true))
        assertEquals(Route.NONE, route(busy = true, blacklist = false))
        assertEquals(Route.NONE, route(skip = true, dnd = true))
        assertEquals(Route.NONE, route(blacklist = true, dnd = true))
        assertEquals(Route.NONE, route(blacklist = false, skip = true, dnd = true))
        assertEquals(Route.BLACKLIST, route(blacklist = false))
    }
}
