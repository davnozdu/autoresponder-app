package com.davnozdu.autoresponder.msgrec

import android.os.Looper

/** Shell-only, idle-phone check. No call actions, messages or persistent volume changes. */
object MsgrOwnerAudioProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (Looper.myLooper() == null) Looper.prepare()
        VoipOwnerAudio().use { outer ->
            val beforeMute = outer.isMuted()
            val beforeVolume = outer.volume()
            repeat(2) {
                VoipOwnerAudio().use { audio ->
                    audio.setSilent(true)
                    check(audio.isMuted() && audio.volume() == 0 && audio.isMicMuted())
                    Thread.sleep(300)
                    audio.setSilent(false)
                    check(audio.isMuted() == beforeMute && audio.volume() == beforeVolume)
                    audio.setSilent(true) // close() must restore an active mute too.
                }
                check(outer.isMuted() == beforeMute && outer.volume() == beforeVolume)
            }
            outer.setSilent(true)
            VoipOwnerAudio().use { nested -> nested.setSilent(true) }
            check(outer.isMuted() && outer.volume() == 0) // Preserve an existing mute.
            outer.setSilent(false)
            check(outer.isMuted() == beforeMute && outer.volume() == beforeVolume)
            println("OWNER_AUDIO_PROBE PASS mute/volume=0/restore/repeat/preexisting-mute volume=$beforeVolume")
        }
        kotlin.system.exitProcess(0)
    }
}
