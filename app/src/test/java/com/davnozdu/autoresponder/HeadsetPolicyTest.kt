package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.rules.HeadsetPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetPolicyTest {

    @Test fun `срабатывает только когда включено, гарнитура есть и звонящий не избранный`() {
        assertTrue(HeadsetPolicy.shouldForceAnswer(enabled = true, headsetConnected = true, skip = false))
    }

    @Test fun `выключенная настройка не срабатывает даже с гарнитурой`() {
        assertFalse(HeadsetPolicy.shouldForceAnswer(enabled = false, headsetConnected = true, skip = false))
    }

    @Test fun `без гарнитуры не срабатывает`() {
        assertFalse(HeadsetPolicy.shouldForceAnswer(enabled = true, headsetConnected = false, skip = false))
    }

    @Test fun `избранных не трогает даже при гарнитуре`() {
        assertFalse(HeadsetPolicy.shouldForceAnswer(enabled = true, headsetConnected = true, skip = true))
    }
}
