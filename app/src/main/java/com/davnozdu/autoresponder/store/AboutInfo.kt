package com.davnozdu.autoresponder.store

import android.content.Context
import java.io.File

/**
 * «О компании» для LLM. Источник (по приоритету):
 *  1) загруженная через приложение копия: filesDir/about.md;
 *  2) публичный путь /sdcard/AutoResponder/about.md (положить вручную; доступ к файлам даёт модуль);
 *  3) fallback — businessInfo из настроек.
 * Кэшируется по (путь+mtime+size), чтобы не читать диск на каждое сообщение.
 */
object AboutInfo {
    const val PUBLIC_PATH = "/sdcard/AutoResponder/about.md"

    @Volatile private var cacheStamp: String = ""
    @Volatile private var cacheText: String = ""

    private fun appCopy(context: Context) = File(context.filesDir, "about.md")

    /** Итоговый текст «о компании»: файл (если есть и непустой) либо [fallback]. */
    fun text(context: Context, fallback: String): String {
        val f = pick(context) ?: return fallback
        return try {
            val stamp = "${f.path}:${f.lastModified()}:${f.length()}"
            if (stamp != cacheStamp) {
                val t = f.readText().trim()
                cacheText = t
                cacheStamp = stamp
            }
            cacheText.ifBlank { fallback }
        } catch (_: Exception) { fallback }
    }

    /** Есть ли активный .md-источник (для UI/лога). */
    fun source(context: Context): String? = pick(context)?.path

    private fun pick(context: Context): File? {
        val app = appCopy(context)
        if (app.exists() && app.length() > 0) return app
        return try {
            val pub = File(PUBLIC_PATH)
            if (pub.exists() && pub.length() > 0) pub else null
        } catch (_: Exception) { null }
    }

    /** Сохранить загруженный через приложение текст (или очистить, если пусто). */
    fun saveAppCopy(context: Context, text: String?) {
        val f = appCopy(context)
        try {
            if (text.isNullOrBlank()) { f.delete() } else { f.writeText(text) }
            cacheStamp = ""  // сбросить кэш
        } catch (_: Exception) {}
    }
}
