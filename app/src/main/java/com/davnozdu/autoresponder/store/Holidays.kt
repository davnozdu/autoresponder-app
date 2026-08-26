package com.davnozdu.autoresponder.store

import android.content.Context
import java.io.File

/**
 * Список гос. праздников/выходных для LLM (когда офис закрыт / только по договорённости).
 * Источник (по приоритету): filesDir/holidays.txt (загруженный в приложении) →
 * /sdcard/AutoResponder/holidays.txt → пусто.
 *
 * Формат файла (одна запись в строке; # и пустые строки игнорируются):
 *   MM-DD Название        — ежегодно (напр. 01-01 Nový rok)
 *   YYYY-MM-DD Название    — конкретная дата (напр. 2026-04-06 Velikonoční pondělí)
 */
object Holidays {
    const val PUBLIC_PATH = "/sdcard/AutoResponder/holidays.txt"

    @Volatile private var stamp = ""
    @Volatile private var cache = ""

    private fun appCopy(context: Context) = File(context.filesDir, "holidays.txt")

    private fun pick(context: Context): File? {
        val app = appCopy(context)
        if (app.exists() && app.length() > 0) return app
        return try { File(PUBLIC_PATH).takeIf { it.exists() && it.length() > 0 } } catch (_: Exception) { null }
    }

    /** Сырой список (для промпта LLM); пусто, если файла нет. */
    fun text(context: Context): String {
        val f = pick(context) ?: return ""
        return try {
            val st = "${f.path}:${f.lastModified()}:${f.length()}"
            if (st != stamp) {
                cache = f.readText()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .joinToString("\n")
                stamp = st
            }
            cache
        } catch (_: Exception) { "" }
    }

    fun source(context: Context): String? = pick(context)?.path

    fun saveAppCopy(context: Context, text: String?) {
        val f = appCopy(context)
        try {
            if (text.isNullOrBlank()) f.delete() else f.writeText(text)
            stamp = ""
        } catch (_: Exception) {}
    }
}
