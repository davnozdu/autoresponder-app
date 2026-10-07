package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.msgrec.IncomingCallPolicy
import org.junit.Assert.*
import org.junit.Test

class IncomingCallPolicyTest {
    @Test fun acceptsWhatsappVoiceAnswerWithDeclineInEitherOrder() {
        assertEquals(1, IncomingCallPolicy.legacyAnswerIndex(true, 0, false,
            "Voice call", listOf("Decline", "Answer")))
        assertEquals(0, IncomingCallPolicy.legacyAnswerIndex(true, 0, false,
            "Голосовой звонок", listOf("Ответить", "Отклонить")))
    }
    @Test fun acceptsIncomingCzechCallWithoutDeclineButton() {
        assertEquals(0, IncomingCallPolicy.legacyAnswerIndex(true, 0, false,
            "Příchozí hlasový hovor", listOf("Přijmout")))
    }
    @Test fun rejectsMessagesOngoingCallsAndOutgoingCalls() {
        assertNull(IncomingCallPolicy.legacyAnswerIndex(false, 0, false,
            "Incoming message", listOf("Answer", "Decline")))
        for (type in listOf(2, 3)) assertNull(IncomingCallPolicy.legacyAnswerIndex(true, type, false,
            "Incoming call", listOf("Answer", "Decline")))
        assertNull(IncomingCallPolicy.legacyAnswerIndex(true, 0, false,
            "Исходящий звонок", listOf("Ответить", "Отклонить")))
        assertNull(IncomingCallPolicy.legacyAnswerIndex(true, 0, false,
            "Voice call", listOf("Answer")))
    }
    @Test fun rejectsVideoAndAmbiguousAnswerButtons() {
        assertNull(IncomingCallPolicy.legacyAnswerIndex(true, 0, true,
            "Incoming call", listOf("Answer", "Decline")))
        assertNull(IncomingCallPolicy.legacyAnswerIndex(true, 0, false,
            "Входящий видеозвонок", listOf("Ответить", "Отклонить")))
        assertNull(IncomingCallPolicy.legacyAnswerIndex(true, 0, false,
            "Incoming call", listOf("Answer", "Accept", "Decline")))
    }
}
