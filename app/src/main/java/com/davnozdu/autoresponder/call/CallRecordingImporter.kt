package com.davnozdu.autoresponder.call

import android.content.Context
import com.davnozdu.autoresponder.data.LogFile
import com.davnozdu.autoresponder.store.HistoryDb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Surfaces the phone dialer's own call recordings (OxygenOS auto-record) in the app's journal so
 * they can be transcribed, saved and shared from one place — instead of digging into the dialer,
 * exporting the file and transcribing it by hand.
 *
 * Files are referenced IN PLACE (no copy): the recording already lives on flash, and copying it
 * again would only waste it. A row is inserted with reason "call". Answering-machine calls are
 * left out: RecordingLinker records each original it consumed in `rec_linked`, and those are skipped
 * here so a call handled by the robot is not listed twice.
 *
 * OxygenOS names a recording `<contact-or-+E164>-yyMMddHHmm.<ext>`. The embedded stamp is used when
 * it parses; otherwise the file's last-modified time. The readable caller stays in the journal row.
 */
object CallRecordingImporter {
    private val REC_DIRS = listOf(
        "/sdcard/Music/Recordings/Call Recordings",
        "/storage/emulated/0/Music/Recordings/Call Recordings",
        "/sdcard/MIUI/sound_recorder/call_rec",
    )
    private val AUDIO_EXT = setOf("mp3", "m4a", "amr", "aac", "wav", "ogg")
    // trailing "-2609291314" (yyMMddHHmm) before the extension
    private val stampPattern = Regex("^(.*)-(\\d{10})$")

    @Synchronized
    fun run(context: Context) {
        val db = HistoryDb.get(context)
        var added = 0
        for (dirPath in REC_DIRS) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            for (file in dir.listFiles().orEmpty()) {
                if (!file.isFile || file.length() <= 0L) continue
                if (file.name.startsWith(".")) continue
                if (file.extension.lowercase() !in AUDIO_EXT) continue
                if (db.recLinkedHas(file.name)) continue            // owned by the answering machine
                if (db.amRecHasFile(file.absolutePath)) continue    // already imported
                val (caller, ts) = parse(file)
                val duration = RecordingLinker.durationMs(file.absolutePath)
                val number = if (caller.startsWith("+") || caller.all { it.isDigit() }) caller else null
                val name = if (number == null) caller else null
                if (db.amRecInsert(number, name, ts, duration, file.absolutePath, "call", heard = true) > 0)
                    added++
            }
        }
        if (added > 0) LogFile.append("call-import: добавлено записей звонилки в журнал: $added")
    }

    private fun parse(file: File): Pair<String, Long> {
        val base = file.nameWithoutExtension
        val m = stampPattern.matchEntire(base)
        if (m != null) {
            val ts = runCatching {
                SimpleDateFormat("yyMMddHHmm", Locale.US).apply { isLenient = false }
                    .parse(m.groupValues[2])?.time
            }.getOrNull()
            if (ts != null) return m.groupValues[1].trim().ifEmpty { "Unknown" } to ts
        }
        return base.trim().ifEmpty { "Unknown" } to file.lastModified()
    }
}
