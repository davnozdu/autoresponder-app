package com.davnozdu.autoresponder.msgrec

import android.content.Context
import com.davnozdu.autoresponder.data.LogFile
import com.davnozdu.autoresponder.store.HistoryDb
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale

/** Reattach completed recordings if the app died after the shell host saved the WAV. */
object MsgrRecordingRecovery {
    private val dir = File("/sdcard/Music/Recordings/Messenger")
    // Current names are English ("Messenger_…"); the old Cyrillic prefix is still matched so
    // recordings made before this update are not stranded outside the journal.
    private val namePattern = Regex("^(?:Messenger|Мессенджер)_(.+)_(\\d{8}-\\d{6}-\\d{3})\\.wav$")

    @Synchronized
    fun run(context: Context) {
        for (file in dir.listFiles().orEmpty()) {
            val match = namePattern.matchEntire(file.name) ?: continue
            val duration = durationMs(file) ?: continue
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply { isLenient = false }
            val startedAt = runCatching { stamp.parse(match.groupValues[2])?.time }.getOrNull()
                ?: file.lastModified()
            val displayName = match.groupValues[1].replace('_', ' ')
            if (insertIfMissing(context, displayName, startedAt, duration, file.absolutePath))
                LogFile.append("msgrec: восстановлена запись $file → журнал")
        }
    }

    @Synchronized
    fun insertIfMissing(context: Context, name: String, ts: Long, durationMs: Long, path: String): Boolean {
        val db = HistoryDb.get(context)
        if (db.amRecHasFile(path)) return false
        return db.amRecInsert(null, name, ts, durationMs, path, "messenger", heard = false) > 0
    }

    private fun durationMs(file: File): Long? {
        if (file.length() < 48) return null
        return runCatching {
            RandomAccessFile(file, "r").use { wav ->
                val header = ByteArray(44)
                wav.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(header, 8, 4, Charsets.US_ASCII) != "WAVE" ||
                    String(header, 36, 4, Charsets.US_ASCII) != "data") return@runCatching null
                fun le32(offset: Int): Long = (0..3).fold(0L) { v, i ->
                    v or ((header[offset + i].toLong() and 255L) shl (i * 8))
                }
                if (le32(24) != 48_000L) return@runCatching null
                val dataBytes = le32(40)
                if (dataBytes < 48_000L || dataBytes > file.length() - 44) return@runCatching null
                dataBytes * 1000 / (48_000 * 2)
            }
        }.getOrNull()
    }
}
