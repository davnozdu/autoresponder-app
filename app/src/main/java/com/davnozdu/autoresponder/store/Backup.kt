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

    private fun prefs(context: Context) = context.getSharedPreferences("backup_health", Context.MODE_PRIVATE)
    private fun day() = java.time.LocalDate.now().toString()

    /** VACUUM INTO gives a transactionally consistent snapshot, without copying a live WAL. */
    private fun snapshot(context: Context, destination: File) {
        check(!destination.exists()) { "Файл снимка уже существует" }
        snapshotDatabase(HistoryDb.get(context).writableDatabase, destination)
    }

    internal fun snapshotDatabase(db: android.database.sqlite.SQLiteDatabase, destination: File) {
        db.execSQL("VACUUM INTO ?", arrayOf(destination.absolutePath))
        validate(destination)
    }

    fun validate(file: File) {
        check(file.isFile && file.length() > 0) { "Пустой бэкап" }
        android.database.sqlite.SQLiteDatabase.openDatabase(file,
            android.database.sqlite.SQLiteDatabase.OpenParams.Builder()
                .setOpenFlags(android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                .setSynchronousMode("FULL")
                .setErrorHandler { throw android.database.sqlite.SQLiteDatabaseCorruptException("Бэкап повреждён; исходный файл сохранён") }
                .build()).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "Бэкап повреждён" }
            }
            check(db.version == 9) { "Неподдерживаемая версия базы: ${db.version}; нужна 9" }
            for (table in TABLES) db.rawQuery("SELECT * FROM $table LIMIT 0",null).use { }
        }
    }

    @Synchronized fun run(context: Context, scheduled: Boolean = false): File? {
        val health = prefs(context)
        if (scheduled && health.getString("daily_day", "") == day()) return null
        val s = Settings(context)
        var temporary: File? = null
        return try {
            val dir = File(DIR); check(dir.exists() || dir.mkdirs()) { "Не удалось создать папку бэкапов" }
            val dst = File(dir, "$PREFIX${stampFmt().format(Date())}$SUFFIX")
            val tmp = File(dir, ".${dst.name}.${System.nanoTime()}.tmp"); temporary = tmp
            snapshot(context, tmp)
            check(tmp.renameTo(dst)) { "Не удалось завершить запись бэкапа" }
            val now = System.currentTimeMillis()
            health.edit().putLong("verified_at",now).putString("verified_name",dst.name)
                .putString("daily_day",day()).putString("error", "").commit()
            s.lastBackup = now
            list().drop(s.backupKeep.coerceAtLeast(1)).forEach { it.delete() }
            EventLog(context).add("BACKUP: проверен ${dst.name} (${dst.length()/1024} КБ)")
            dst
        } catch (e: Exception) {
            health.edit().putString("error",e.message ?: e.javaClass.simpleName).apply()
            EventLog(context).add("BACKUP ошибка: ${e.message}"); null
        } finally { temporary?.delete() }
    }

    /** Restore rows transactionally: existing readers/writers keep the same database handle. */
    @Synchronized fun restore(context: Context, backup: File): Boolean {
        val staging = File(context.filesDir, "restore-candidate.db")
        return try {
            backup.copyTo(staging, overwrite=true)
            validate(staging)
            val safetyDir=File(context.filesDir,"restore-safety").apply { mkdirs() }
            val safety=File(safetyDir,"history-${System.currentTimeMillis()}.db")
            snapshot(context,safety)
            val db=HistoryDb.get(context).writableDatabase
            restoreTables(db, staging)
            PersonThreads.invalidate()
            safetyDir.listFiles()?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
            EventLog(context).add("BACKUP: восстановлено ${backup.name}; предыдущая база сохранена в restore-safety")
            true
        } catch (e: Exception) {
            EventLog(context).add("BACKUP восстановление отменено: ${e.message}"); false
        } finally { staging.delete() }
    }

    internal fun restoreTables(db: android.database.sqlite.SQLiteDatabase, staging: File) {
        validate(staging)
            db.execSQL("ATTACH DATABASE ? AS restore_src",arrayOf(staging.absolutePath))
            try {
                // Check exact columns before touching the live database.
                for(table in TABLES) {
                    val live=db.rawQuery("SELECT * FROM main.$table LIMIT 0",null).use { it.columnNames.toList() }
                    val source=db.rawQuery("SELECT * FROM restore_src.$table LIMIT 0",null).use { it.columnNames.toList() }
                    check(live==source) { "Несовместимая таблица $table" }
                }
                db.beginTransaction()
                try {
                    for(table in TABLES) {
                        db.execSQL("DELETE FROM main.$table")
                        db.execSQL("INSERT INTO main.$table SELECT * FROM restore_src.$table")
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { db.execSQL("DETACH DATABASE restore_src") }
    }

    fun health(context: Context): String {
        val p=prefs(context)
        val at=p.getLong("verified_at",0)
        val error=p.getString("error","").orEmpty()
        return (if(at==0L) "Проверенный бэкап ещё не создан" else "Проверен: ${p.getString("verified_name","")}") +
            if(error.isBlank()) "" else "\nОшибка: $error"
    }
    private val TABLES=listOf("events","blacklist","qa","bl_pending","sms_hold","inbox_done")

    /** Запланировать следующий ежедневный бэкап (идемпотентно). */
    fun schedule(context: Context) {
        val s = Settings(context)
        if (!s.backupEnabled) { cancel(context); return }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.backupHour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() || prefs(context).getString("daily_day", "") == day()) add(Calendar.DAY_OF_YEAR, 1)
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
