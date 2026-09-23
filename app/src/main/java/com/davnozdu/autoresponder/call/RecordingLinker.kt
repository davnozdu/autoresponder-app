package com.davnozdu.autoresponder.call

import android.content.Context
import android.media.MediaMetadataRetriever
import com.davnozdu.autoresponder.data.EventLog
import java.io.File

/**
 * Находит запись, сделанную встроенной автозаписью звонилки для только что завершённого
 * звонка, и копирует её в отдельную папку, чтобы владелец слушал сообщения автоответчика
 * не роясь в общем архиве.
 *
 * Своя запись НЕ ведётся — используется штатная автозапись OxygenOS (у неё привилегии OEM и
 * тап INCALL_RECORD в DSP; см. заметки). Файл появляется ПОСЛЕ отбоя, поэтому ждём его.
 *
 * Активная папка на целевом телефоне: /sdcard/Music/Recordings/Call Recordings/
 * Имя: `<имя|+E164>-ДДММГГЧЧММ.mp3`. Незнакомый (ЧС) номер обычно не в контактах —
 * тогда имя файла содержит сам номер, по нему и матчим; иначе берём свежайший после звонка.
 */
object RecordingLinker {
    private val REC_DIRS = listOf(
        "/sdcard/Music/Recordings/Call Recordings",
        "/storage/emulated/0/Music/Recordings/Call Recordings",
        "/sdcard/MIUI/sound_recorder/call_rec"
    )
    private const val DEST = "/sdcard/AutoResponder/recordings"
    private val AUDIO_EXT = setOf("mp3", "m4a", "amr", "aac", "wav", "ogg")

    /**
     * Ждёт появления файла записи и копирует его.
     * @param sinceMs время начала звонка (файл должен быть не старше).
     * @param waitMs сколько ждать финализации файла звонилкой.
     * @return (путь к копии, длительность мс) или null.
     */
    fun linkLatest(ctx: Context, number: String?, sinceMs: Long, waitMs: Long = 12_000L): Pair<String, Long>? {
        val app = ctx.applicationContext
        val digits = number?.filter { it.isDigit() }?.takeLast(9) ?: ""
        val deadline = System.currentTimeMillis() + waitMs
        var found: File? = null
        while (System.currentTimeMillis() < deadline) {
            found = pickRecording(digits, sinceMs)
            if (found != null && found.length() > 0) {
                // файл может ещё дописываться — подождём стабилизации размера
                val sz1 = found.length(); Thread.sleep(600)
                if (found.length() == sz1) break
            }
            Thread.sleep(500)
        }
        val src = found ?: run { EventLog(app).add("AM запись: файл не найден"); return null }
        return try {
            val dstDir = File(DEST).apply { mkdirs() }
            val ext = src.extension.ifBlank { "mp3" }
            val safeNum = (number ?: "unknown").replace(Regex("[^+0-9]"), "")
            val name = "${tsName(sinceMs)}_${safeNum}.$ext"
            val dst = File(dstDir, name)
            src.copyTo(dst, overwrite = true)
            val dur = durationMs(dst.absolutePath)
            EventLog(app).add("AM запись: ${src.name} → ${dst.name} (${dur/1000}s)")
            dst.absolutePath to dur
        } catch (e: Exception) {
            EventLog(app).add("AM запись: не скопировал (${e.message})"); null
        }
    }

    private fun pickRecording(digits: String, sinceMs: Long): File? {
        val candidates = ArrayList<File>()
        for (p in REC_DIRS) {
            val d = File(p); if (!d.isDirectory) continue
            d.listFiles()?.forEach { f ->
                if (f.isFile && f.extension.lowercase() in AUDIO_EXT &&
                    f.lastModified() >= sinceMs - 2_000L) candidates.add(f)
            }
        }
        if (candidates.isEmpty()) return null
        // сначала — по номеру в имени, затем — свежайший
        if (digits.length >= 6) {
            candidates.filter { it.name.filter(Char::isDigit).contains(digits) }
                .maxByOrNull { it.lastModified() }?.let { return it }
        }
        return candidates.maxByOrNull { it.lastModified() }
    }

    /** Публичный доступ для своей (pal_record) записи — тот же способ узнать длительность,
     *  что используется для штатной. */
    fun durationMs(path: String): Long = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    } catch (e: Exception) { 0L }

    private fun tsName(ts: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(ts))
}
