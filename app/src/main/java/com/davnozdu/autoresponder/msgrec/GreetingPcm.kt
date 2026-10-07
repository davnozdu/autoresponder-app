package com.davnozdu.autoresponder.msgrec

/** Greeting.prepare produces signed little-endian PCM16 stereo at 48 kHz. */
internal object GreetingPcm {
    fun mono(stereo: ByteArray): ByteArray {
        require(stereo.isNotEmpty() && stereo.size % 4 == 0) { "invalid stereo PCM" }
        require(stereo.size / 2 <= VoipAudioInjector.MAX_BYTES) { "greeting exceeds 120 seconds" }
        val mono = ByteArray(stereo.size / 2)
        for (frame in 0 until stereo.size / 4) {
            val p = frame * 4
            val left = ((stereo[p].toInt() and 255) or (stereo[p + 1].toInt() shl 8)).toShort().toInt()
            val right = ((stereo[p + 2].toInt() and 255) or (stereo[p + 3].toInt() shl 8)).toShort().toInt()
            val v = (left + right) / 2
            mono[frame * 2] = v.toByte()
            mono[frame * 2 + 1] = (v shr 8).toByte()
        }
        return mono
    }
}
