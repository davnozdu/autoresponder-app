package com.davnozdu.autoresponder.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Файловая копия журнала: `/sdcard/AutoResponder/logs/autoresp-YYYY-MM-DD.log`.
 *
 * Сам [EventLog] живёт в оперативной памяти и исчезает вместе с процессом. Для отладки
 * этого хватало, но вопрос «почему клиенту вчера не ответили» разбирать уже нечем:
 * к моменту, когда о нём узнаёшь, буфер давно перезаписан. Поэтому те же строки
 * дублируются в файл — по одному на сутки, старые удаляются.
 *
 * Запись асинхронная и «мягкая»: журналом занимается фоновый поток, и любая ошибка
 * файловой системы молча игнорируется. Автоответ не должен ломаться из-за того,
 * что не примонтирована карта или отозван доступ к файлам.
 */
object LogFile {

    const val DIR = "/sdcard/AutoResponder/logs"
    private const val PREFIX = "autoresp-"
    private const val SUFFIX = ".log"

    /** Дней хранения. Неделя — столько же, сколько живёт разговор с клиентом. */
    var keepDays: Int = 7

    @Volatile var enabled: Boolean = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "autoresp-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Файл текущих суток. */
    fun today(): File = File(DIR, "$PREFIX${dayFmt.format(Date())}$SUFFIX")

    fun files(): List<File> =
        File(DIR).listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun append(line: String) {
        if (!enabled) return
        val now = Date()
        io.execute {
            try {
                val dir = File(DIR)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "$PREFIX${dayFmt.format(now)}$SUFFIX")
                val fresh = !f.exists()
                f.appendText("${timeFmt.format(now)}  $line\n")
                if (fresh) rotate()     // сутки сменились — самое время убрать старое
            } catch (_: Exception) { /* журнал не должен мешать работе */ }
        }
    }

    /** Удалить всё, что старше [keepDays] суток. */
    fun rotate() {
        try {
            files().drop(keepDays.coerceAtLeast(1)).forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun clear() {
        io.execute { try { files().forEach { it.delete() } } catch (_: Exception) {} }
    }

    /** Сколько занимают файлы журнала — для строки в настройках. */
    fun sizeBytes(): Long = try { files().sumOf { it.length() } } catch (_: Exception) { 0L }
}
