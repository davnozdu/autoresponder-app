package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.msgrec.GreetingPcm
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GreetingPcmTest {
    @Test fun preservesSignedSamplesAndAveragesWithoutOverflow() {
        val stereo = byteArrayOf(-1, 127, -1, 127, 0, -128, 0, -128, -1, 127, 0, -128, 0, 16, 0, 0)
        assertArrayEquals(byteArrayOf(-1, 127, 0, -128, 0, 0, 0, 8), GreetingPcm.mono(stereo))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPartialFrames() {
        GreetingPcm.mono(byteArrayOf(1, 2, 3))
    }
}
