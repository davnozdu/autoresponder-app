/*
 * Messenger (VoIP) far-party capture — derived from CallVault (GPL-3.0 + §7),
 * a fork of ShizuCallRecorder. See LICENSE and NOTICE.md at the repo root.
 *
 * This file is a port of CallVault's server/BypassedAudioRecord.kt into AutoResponder
 * (only the package changed). CallVault credits this as a port of scrcpy's
 * Workarounds.createAudioRecord (Genymobile/scrcpy PR #5154, Apache-2.0).
 *
 *  Copyright (C) 2026-present The CallVault Authors
 *  Copyright (C) 2026-present davnozdu (AutoResponder modifications)
 *  Licensed under the GNU General Public License v3 or later, with additional terms
 *  as permitted under Section 7. This program is distributed WITHOUT ANY WARRANTY.
 */

package com.davnozdu.autoresponder.msgrec

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.media.AudioAttributes
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import java.lang.ref.WeakReference

/**
 * Builds an [AudioRecord] without running its public constructor.
 *
 * **Why.** vivo (OriginOS / Funtouch) modified `AudioRecord`'s constructor to call
 * `VivoAudioRecordImpl.isSupportSubMixRecording()`, which dereferences the record's `Context` with no null
 * check. The recorder host has no Context, so on vivo every app-call recording failed at the far-party sink:
 * `NullPointerException … Context.getOpPackageName()` (iQOO V2507A, OriginOS 6, Android 16).
 *
 * **Where this comes from.** A port of scrcpy's `Workarounds.createAudioRecord` (Genymobile/scrcpy PR #5154,
 * Apache License 2.0). It creates the object through the package-private `AudioRecord(long)` constructor and
 * initialises it by reflection exactly as the public constructor would, so the vendor code never runs.
 *
 * Used only after the normal path has failed — never the first choice.
 *
 * **Hidden APIs.** This runs only inside the recorder host — an `app_process` started as a privileged user
 * (shell or root), never in the app process. The hidden-API policy the app is subject to does not govern
 * that host, which already reaches `AudioPolicy` the same way.
 */
@SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi", "PrivateApi", "BlockedPrivateApi")
internal object BypassedAudioRecord {

    /** The tag the public constructor strips and turns into `mIsSubmixFullVolume`. Hidden constant, same value on 11–16. */
    private const val SUBMIX_FIXED_VOLUME = "fixedVolume"

    /**
     * @param capturePreset the `MediaRecorder.AudioSource` preset the attributes carry.
     * @param tags          attribute tags, e.g. the mix address; [SUBMIX_FIXED_VOLUME] is handled like AOSP does.
     * @param channelMask   an IN channel mask.
     */
    fun create(
        capturePreset: Int,
        tags: List<String>,
        sampleRate: Int,
        channelMask: Int,
        channelCount: Int,
        encoding: Int,
        bufferSizeInBytes: Int,
    ): AudioRecord {
        val cls = AudioRecord::class.java
        val record = cls.getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }
            .newInstance(0L)

        field("mRecordingState").set(record, AudioRecord.RECORDSTATE_STOPPED)
        field("mInitializationLooper").set(record, Looper.myLooper() ?: Looper.getMainLooper())

        val fullVolume = tags.any { it.equals(SUBMIX_FIXED_VOLUME, ignoreCase = true) }
        if (fullVolume) runCatching { field("mIsSubmixFullVolume").setBoolean(record, true) }

        val ab = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(ab, capturePreset)
        val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        tags.filterNot { it.equals(SUBMIX_FIXED_VOLUME, ignoreCase = true) }.forEach { addTag.invoke(ab, it) }
        val attributes = ab.build()
        field("mAudioAttributes").set(record, attributes)

        cls.getDeclaredMethod("audioParamCheck", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(record, capturePreset, sampleRate, encoding)
        field("mChannelCount").setInt(record, channelCount)
        field("mChannelMask").setInt(record, channelMask)
        cls.getDeclaredMethod("audioBuffSizeCheck", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(record, bufferSizeInBytes)

        val rates = intArrayOf(sampleRate)
        val session = intArrayOf(android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        val nativeBuffer = field("mNativeBufferSizeInBytes").getInt(record)
        val result = nativeSetup(record, attributes, rates, channelMask, record.audioFormat, nativeBuffer, session)
        check(result == AudioRecord.SUCCESS) { "native_setup returned $result" }

        field("mSampleRate").setInt(record, rates[0])
        field("mSessionId").setInt(record, session[0])
        field("mState").setInt(record, AudioRecord.STATE_INITIALIZED)
        return record
    }

    private fun field(name: String) =
        AudioRecord::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun nativeSetup(
        record: AudioRecord, attributes: AudioAttributes, rates: IntArray, channelMask: Int,
        format: Int, bufferBytes: Int, session: IntArray,
    ): Int {
        val cls = AudioRecord::class.java
        val i = Int::class.javaPrimitiveType
        val l = Long::class.javaPrimitiveType
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 11: (this, attributes, rate[], mask, indexMask, format, bytes, session[], opPackageName, nativePtr)
            val opPackage = runCatching {
                Class.forName("android.app.ActivityThread").getMethod("currentOpPackageName").invoke(null) as String?
            }.getOrNull()
            return cls.getDeclaredMethod("native_setup", Any::class.java, Any::class.java, IntArray::class.java, i, i, i, i,
                IntArray::class.java, String::class.java, l).apply { isAccessible = true }
                .invoke(record, WeakReference(record), attributes, rates, channelMask, 0, format, bufferBytes, session, opPackage, 0L) as Int
        }
        var source = AttributionSource.myAttributionSource()
        if (source.packageName == null) {
            // What the public constructor does for a command-line caller (Android 13 and later).
            source = runCatching {
                AttributionSource::class.java.getMethod("withPackageName", String::class.java)
                    .invoke(source, "uid:" + Binder.getCallingUid()) as AttributionSource
            }.getOrDefault(source)
        }
        val state = AttributionSource::class.java.getDeclaredMethod("asScopedParcelState").apply { isAccessible = true }
            .invoke(source) as AutoCloseable
        return state.use {
            val parcel = it.javaClass.getDeclaredMethod("getParcel").apply { isAccessible = true }.invoke(it) as Parcel
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 12–13: (…, session[], Parcel attributionSource, nativePtr, maxSharedAudioHistoryMs)
                cls.getDeclaredMethod("native_setup", Any::class.java, Any::class.java, IntArray::class.java, i, i, i, i,
                    IntArray::class.java, Parcel::class.java, l, i).apply { isAccessible = true }
                    .invoke(record, WeakReference(record), attributes, rates, channelMask, 0, format, bufferBytes, session, parcel, 0L, 0) as Int
            } else {
                // Android 14+ added halInputFlags.
                cls.getDeclaredMethod("native_setup", Any::class.java, Any::class.java, IntArray::class.java, i, i, i, i,
                    IntArray::class.java, Parcel::class.java, l, i, i).apply { isAccessible = true }
                    .invoke(record, WeakReference(record), attributes, rates, channelMask, 0, format, bufferBytes, session, parcel, 0L, 0, 0) as Int
            }
        }
    }
}
