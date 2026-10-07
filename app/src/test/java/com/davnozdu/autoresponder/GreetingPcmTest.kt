package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.msgrec.GreetingPcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import com.davnozdu.autoresponder.msgrec.VoipAudioInjector
import org.junit.Test

class GreetingPcmTest {
    @Test fun preservesSignedSamplesAndAveragesWithoutOverflow() {
        val stereo = byteArrayOf(-1, 127, -1, 127, 0, -128, 0, -128, -1, 127, 0, -128, 0, 16, 0, 0)
        assertArrayEquals(byteArrayOf(-1, 127, 0, -128, 0, 0, 0, 8), GreetingPcm.mono(stereo))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPartialFrames() {
        GreetingPcm.mono(byteArrayOf(1, 2, 3))
    }
    @Test fun convertsFiveMinuteScreeningHoldWithGreetingForPersistentSession() {
        val stereo = ByteArray(48_000 * 4 * 305)
        val last = stereo.size - 4
        stereo[last] = 0x34; stereo[last + 1] = 0x12
        stereo[last + 2] = 0x34; stereo[last + 3] = 0x12
        val mono = GreetingPcm.mono(stereo, VoipAudioInjector.MAX_SESSION_CLIP_BYTES)
        assertEquals(stereo.size / 2, mono.size)
        assertEquals(0x34, mono[mono.size - 2].toInt())
        assertEquals(0x12, mono[mono.size - 1].toInt())
    }
    @Test(expected = IllegalArgumentException::class) fun legacyGreetingStillRejectsOverTwoMinutes() {
        GreetingPcm.mono(ByteArray((48_000 * 120 + 1) * 4))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPayloadBeyondSelectedLimit() {
        GreetingPcm.mono(ByteArray(12), maxMonoBytes = 4)
    }
}
