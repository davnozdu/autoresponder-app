package com.davnozdu.autoresponder.msgrec

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UID-scoped microphone replacement. Independent of the working far-party capture policy.
 * Uses the AOSP AudioPolicy MIX_ROLE_INJECTOR/createAudioTrackSource mechanism (hidden API).
 * Register BEFORE answering: the messenger must create its recorder on this route.
 */
internal class VoipAudioInjector(uid: Int, private val pcm: ByteArray) : Closeable {
    private val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
    private var policy: Any? = null
    private var track: AudioTrack? = null
    @Volatile private var running = true
    @Volatile private var playing = false
    @Volatile private var failure: Throwable? = null
    private val finished = CountDownLatch(1)
    private var worker: Thread? = null

    init {
        require(uid >= 2000)
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= MAX_BYTES)
        try {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val rb = ruleBuilderClass.getConstructor().newInstance()
            ruleBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(rb, 1)
            ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(rb, 4, uid) // RULE_MATCH_UID; never match all microphones.
            val rule = ruleBuilderClass.getMethod("build").invoke(rb)
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mb = mixBuilderClass.getDeclaredConstructor(ruleClass).apply { isAccessible = true }
                .newInstance(rule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mb,
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mb, 2)
            val mix = mixBuilderClass.getMethod("build").invoke(mb)
            val pbClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            // Same shell attribution as VoipAudioPolicy; a fake "android" Context breaks routing.
            val pb = pbClass.getConstructor(Context::class.java).newInstance(null)
            pbClass.getMethod("addMix", mixClass).invoke(pb, mix)
            val p = pbClass.getMethod("build").invoke(pb)
            val rc = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass)
                .apply { isAccessible = true }.invoke(null, p) as Int
            check(rc == AudioManager.SUCCESS) { "inject policy rejected: $rc" }
            policy = p
            val t = policyClass.getMethod("createAudioTrackSource", mixClass).invoke(p, mix) as AudioTrack?
                ?: error("inject source unavailable")
            track = t
            check(t.state == AudioTrack.STATE_INITIALIZED) { "inject source not initialized" }
            t.play()
            worker = Thread({ stream(t) }, "msgr-inject-audio").apply { start() }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    fun play() { playing = true }

    fun awaitDone() {
        check(finished.await(125, TimeUnit.SECONDS)) { "inject playback timeout" }
        failure?.let { throw IllegalStateException("inject playback failed", it) }
    }

    private fun stream(t: AudioTrack) {
        val silence = ByteArray(960 * 2) // 20 ms; no real microphone while greeting is armed.
        var offset = 0
        var lastProgress = SystemClock.elapsedRealtime()
        val deadline = lastProgress + 170_000
        try {
            while (running) {
                check(SystemClock.elapsedRealtime() < deadline) { "inject session expired" }
                val speech = playing
                if (speech && offset == pcm.size) {
                    // Wait for buffered frames to be rendered before restoring the microphone.
                    Thread.sleep(300)
                    return
                }
                val bytes = if (speech) pcm else silence
                val start = if (speech) offset else 0
                val n = t.write(bytes, start, minOf(silence.size, bytes.size - start), AudioTrack.WRITE_NON_BLOCKING)
                check(n >= 0) { "inject write failed: $n" }
                if (n > 0) {
                    if (speech) offset += n
                    lastProgress = SystemClock.elapsedRealtime()
                } else {
                    check(SystemClock.elapsedRealtime() - lastProgress < 5000) { "inject source stalled" }
                    Thread.sleep(5)
                }
            }
        } catch (t: Throwable) {
            failure = t
        } finally { finished.countDown() }
    }

    @Synchronized override fun close() {
        running = false
        worker?.join(1000)
        track?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
        track = null
        policy?.let { p ->
            runCatching {
                AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass)
                    .apply { isAccessible = true }.invoke(null, p)
            }.onFailure { System.err.println("inject unregister failed: ${it.message}") }
        }
        policy = null
    }

    companion object {
        const val RATE = 48_000
        const val MAX_BYTES = RATE * 2 * 120
    }
}
