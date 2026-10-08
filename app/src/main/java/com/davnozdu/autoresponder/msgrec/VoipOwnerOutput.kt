package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import java.io.Closeable

/** The KSU module's shell audio host routes this caller's playback to a discard sink.
 * LOOP_BACK without RENDER removes physical output entirely, including SCO/LE/USB headsets.
 * The pre-existing LOOP_BACK_RENDER capture remains a separate secondary output, so the far
 * party is still recorded. Never replace/reopen that capture sink when muting or handing off.
 * Removing this session policy restores the messenger's normal output routing and volume.
 */
internal class VoipOwnerOutput(uid: Int) : Closeable {
    private val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
    private var policy: Any? = null
    private var sink: AudioRecord? = null
    @Volatile private var running = true
    private var worker: Thread? = null

    init {
        require(uid >= 2000)
        try {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val rbClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val rb = rbClass.getConstructor().newInstance()
            rbClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(rb, 0)
            val add = rbClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            add.invoke(rb, 4, uid) // RULE_MATCH_UID: only the current messenger.
            add.invoke(rb, 1, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            rbClass.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType)
                .invoke(rb, true)
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mbClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mb = mbClass.getDeclaredConstructor(ruleClass).apply { isAccessible = true }
                .newInstance(rbClass.getMethod("build").invoke(rb))
            mbClass.getMethod("setFormat", AudioFormat::class.java).invoke(mb,
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            mbClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mb, 2)
            val mix = mbClass.getMethod("build").invoke(mb)
            val pbClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val pb = pbClass.getConstructor(Context::class.java).newInstance(null)
            pbClass.getMethod("addMix", mixClass).invoke(pb, mix)
            val p = pbClass.getMethod("build").invoke(pb)
            val rc = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass)
                .apply { isAccessible = true }.invoke(null, p) as Int
            check(rc == 0) { "owner output policy rejected: $rc" }
            policy = p
            val record = VoipAudioPolicy.createSink(p, mix) ?: error("owner discard sink unavailable")
            sink = record
            record.startRecording()
            worker = Thread({
                val buffer = ShortArray(960)
                try {
                    while (running) {
                        val n = record.read(buffer, 0, buffer.size)
                        check(n > 0 || !running) { "owner discard read failed: $n" }
                    }
                } catch (t: Exception) {
                    if (running) System.err.println("msgrec owner discard failed: ${t.message}")
                }
            }, "msgr-owner-discard").apply { isDaemon = true; start() }
            println("msgrec owner output isolated uid=$uid (no physical render)")
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override fun close() {
        running = false
        sink?.let { runCatching { it.stop() } }
        worker?.join(1000)
        sink?.let { runCatching { it.release() } }
        sink = null
        policy?.let { p ->
            AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass)
                .apply { isAccessible = true }.invoke(null, p)
            println("msgrec owner output physical render restored")
        }
        policy = null
    }
}
