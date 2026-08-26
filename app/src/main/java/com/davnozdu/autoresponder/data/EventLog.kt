package com.davnozdu.autoresponder.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал событий целиком в оперативной памяти (RAM) — НИКАКОЙ записи на flash.
 * Процесс держится живым за счёт NotificationListenerService, поэтому лог не теряется
 * между событиями, но чистится при перезагрузке/убийстве процесса (он не нужен постоянно).
 * Кольцевой буфер с ротацией по числу строк.
 */
class EventLog(@Suppress("UNUSED_PARAMETER") context: Context? = null) {

    fun add(line: String) = Store.add(line)
    fun all(): String = Store.snapshot()
    fun clear() = Store.clear()

    private object Store {
        private const val MAX = 300
        private val buf = ArrayDeque<String>()
        private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        @Synchronized fun add(line: String) {
            buf.addFirst("${fmt.format(Date())}  $line")
            while (buf.size > MAX) buf.removeLast()
            // Дублируем в logcat: журнал живёт только в RAM, а при разборе проблем удобно
            // смотреть через `adb logcat -s AutoResp`.
            android.util.Log.i("AutoResp", line)
        }
        @Synchronized fun snapshot(): String = buf.joinToString("\n")
        @Synchronized fun clear() = buf.clear()
    }
}
