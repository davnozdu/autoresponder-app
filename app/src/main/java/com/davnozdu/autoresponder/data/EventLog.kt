package com.davnozdu.autoresponder.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Простой кольцевой лог событий для UI/диагностики. */
class EventLog(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("autoresp_log", Context.MODE_PRIVATE)

    fun add(line: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "$ts  $line"
        val cur = sp.getString(KEY, "") ?: ""
        val merged = (entry + "\n" + cur).lines().take(MAX).joinToString("\n")
        sp.edit().putString(KEY, merged).apply()
    }

    fun all(): String = sp.getString(KEY, "") ?: ""
    fun clear() = sp.edit().remove(KEY).apply()

    companion object { private const val KEY = "log"; private const val MAX = 200 }
}
