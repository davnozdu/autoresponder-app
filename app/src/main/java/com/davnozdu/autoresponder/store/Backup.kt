package com.davnozdu.autoresponder.store

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ежедневный бэкап БД истории (context для LLM) в /sdcard/AutoResponder/backups с ротацией.
 * Планировщик — AlarmManager (setAndAllowWhileIdle, пере-планируется после каждого срабатывания).
 */
object Backup {
    const val DIR = "/sdcard/AutoResponder/backups"
    const val ACTION = "com.davnozdu.autoresponder.DAILY_BACKUP"
    private const val REQ = 31
    private const val PREFIX = "history-"
    private const val SUFFIX = ".db"

    private fun stampFmt() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Список бэкапов (новые сверху). */
    fun list(): List<File> = try {
        File(DIR).listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Выполнить бэкап сейчас. Возвращает файл или null. */
    fun run(context: Context): File? {
        val s = Settings(context)
        return try {
            HistoryDb.get(context).checkpoint()  // дозаписать WAL, чтобы копия была полной
            val src = context.getDatabasePath("history.db")
            if (!src.exists()) return null
            val dir = File(DIR); dir.mkdirs()
            val dst = File(dir, "$PREFIX${stampFmt().format(Date())}$SUFFIX")
            src.copyTo(dst, overwrite = true)
            // Ротация: оставить N новейших.
            val keep = s.backupKeep.coerceAtLeast(1)
            list().drop(keep).forEach { runCatching { it.delete() } }
            s.lastBackup = System.currentTimeMillis()
            EventLog(context).add("BACKUP: сохранён ${dst.name} (${dst.length()/1024} КБ), копий ${list().size}/$keep")
            dst
        } catch (e: Exception) {
            EventLog(context).add("BACKUP ошибка: ${e.javaClass.simpleName}: ${e.message}"); null
        }
    }

    /** Восстановить БД из файла бэкапа. */
    fun restore(context: Context, backup: File): Boolean {
        return try {
            if (!backup.exists() || backup.length() == 0L) return false
            val dbFile = context.getDatabasePath("history.db")
            HistoryDb.get(context).checkpoint()
            HistoryDb.reset()  // закрыть соединение
            // Удалить WAL/SHM, чтобы не смешать со старым состоянием.
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            backup.copyTo(dbFile, overwrite = true)
            HistoryDb.get(context)  // пересоздать
            EventLog(context).add("BACKUP: восстановлено из ${backup.name}")
            true
        } catch (e: Exception) {
            EventLog(context).add("BACKUP восстановление ошибка: ${e.message}"); false
        }
    }

    /** Запланировать следующий ежедневный бэкап (идемпотентно). */
    fun schedule(context: Context) {
        val s = Settings(context)
        if (!s.backupEnabled) { cancel(context); return }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.backupHour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(context))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(context))
    }

    private fun pi(context: Context): PendingIntent {
        val i = Intent(context, BackupReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(context, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
