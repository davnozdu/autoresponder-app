package com.davnozdu.autoresponder.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Копирование аудиофайла приветствия/hold-луп в приватную папку приложения (files/am/) —
 * общий код для UI-пикера (Uri, SAF) и ручного adb-импорта (обычный файл, см.
 * [com.davnozdu.autoresponder.ui.ImportAudioActivity]). Раньше эта логика жила только в
 * MainActivity (importGreetingFile) — вынесена сюда, чтобы обе точки входа не расходились.
 */
object AudioImport {
    fun importFromFile(ctx: Context, src: File, targetSlot: String): String? = try {
        val dir = File(ctx.filesDir, "am").apply { mkdirs() }
        val ext = src.extension.ifBlank { "dat" }
        val dst = File(dir, "$targetSlot.$ext")
        src.copyTo(dst, overwrite = true)
        if (dst.length() > 0) dst.absolutePath else null
    } catch (e: Exception) { null }

    fun importFromUri(ctx: Context, uri: Uri, targetSlot: String): String? = try {
        val dir = File(ctx.filesDir, "am").apply { mkdirs() }
        val ext = ctx.contentResolver.getType(uri)?.substringAfterLast('/')?.take(4) ?: "dat"
        val dst = File(dir, "$targetSlot.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
        if (dst.length() > 0) dst.absolutePath else null
    } catch (e: Exception) { null }
}
