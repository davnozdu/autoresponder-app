package com.davnozdu.autoresponder.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ReplyJob(val id: Long, val who: String, val kind: String, val payload: String,
                    val created: Long, val expires: Long)
data class ManualControl(val until: Long, val cutoff: Long)

/** Small durable event journal. Messenger snapshots remain disposable tmpfs files. */
class RuntimeDb internal constructor(context: Context, name: String = "runtime.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT UNIQUE, who TEXT, kind TEXT, payload TEXT, created INTEGER, expires INTEGER, state TEXT, detail TEXT DEFAULT '')")
        db.execSQL("CREATE INDEX jobs_state ON jobs(state,id)")
        db.execSQL("CREATE TABLE manual(identity TEXT PRIMARY KEY, until_ts INTEGER, cutoff INTEGER)")
        db.execSQL("CREATE TABLE aliases(identity TEXT PRIMARY KEY, canonical TEXT)")
        db.execSQL("CREATE TABLE outgoing(id TEXT PRIMARY KEY, identity TEXT, channel TEXT, body TEXT, created INTEGER, state TEXT, detail TEXT, parts INTEGER, job INTEGER, history_key TEXT, limit_key TEXT, timeout INTEGER, committed INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE segments(outgoing TEXT, part INTEGER, sent INTEGER, delivered INTEGER, PRIMARY KEY(outgoing,part))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun enqueue(token: String, who: String, kind: String, payload: String, ttl: Long): Long {
        val now = System.currentTimeMillis()
        // Prune terminal metadata only. Never evict work to make room for new events.
        writableDatabase.delete("jobs", "created < ? AND state NOT IN ('queued','running','sending')", arrayOf((now - 7 * 86_400_000L).toString()))
        writableDatabase.execSQL("DELETE FROM segments WHERE outgoing IN (SELECT id FROM outgoing WHERE created < ?)", arrayOf(now - 30 * 86_400_000L))
        writableDatabase.delete("outgoing", "created < ?", arrayOf((now - 30 * 86_400_000L).toString()))
        return writableDatabase.insertWithOnConflict("jobs", null, ContentValues().apply {
            put("token", token); put("who", who); put("kind", kind); put("payload", payload)
            put("created", now); put("expires", now + ttl); put("state", "queued")
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun recover() {
        writableDatabase.execSQL("UPDATE jobs SET state='queued' WHERE state='running'")
        // A crash between an external side effect and its acknowledgement is ambiguous.
        // Never replay it automatically: that could send a second reply or CRM action.
        writableDatabase.execSQL("UPDATE jobs SET state='uncertain', detail='Перезапуск во время отправки: автоматического повтора нет' WHERE state='sending'")
    }
    @Synchronized fun next(excluded: Set<String>, notificationsReady: Boolean): ReplyJob? {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("UPDATE jobs SET state='expired',detail='Истёк срок актуальности' WHERE state='queued' AND expires < ?", arrayOf(now))
        readableDatabase.rawQuery("SELECT id,who,kind,payload,created,expires FROM jobs WHERE state='queued' ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val identity = canonical(c.getString(1))
                if (identity in excluded || (c.getString(2) == "notification" && !notificationsReady)) continue
                val job = ReplyJob(c.getLong(0), identity, c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))
                state(job.id, "running")
                return job
            }
        }
        return null
    }
    @Synchronized fun state(id: Long, state: String, detail: String = "") {
        if (id <= 0) return
        writableDatabase.update("jobs", ContentValues().apply { put("state", state); put("detail", detail) }, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun finish(id: Long) {
        writableDatabase.execSQL("UPDATE jobs SET state='done',detail=CASE WHEN detail='' THEN 'Обработано по правилам' ELSE detail END WHERE id=? AND state='running'", arrayOf(id))
    }
    fun valid(id: Long): Boolean {
        if (id <= 0) return true
        readableDatabase.rawQuery("SELECT expires FROM jobs WHERE id=?", arrayOf(id.toString())).use { return it.moveToFirst() && it.getLong(0) >= System.currentTimeMillis() }
    }
    fun canonical(identity: String): String = readableDatabase.rawQuery("SELECT canonical FROM aliases WHERE identity=?", arrayOf(identity)).use { if (it.moveToFirst()) it.getString(0) else identity }
    @Synchronized fun bind(identity: String, canonical: String) {
        if (identity == canonical) return
        writableDatabase.insertWithOnConflict("aliases", null, ContentValues().apply { put("identity", identity); put("canonical", canonical) }, SQLiteDatabase.CONFLICT_REPLACE)
        control(identity)?.let { c ->
            val current = control(canonical)
            if (current == null || c.cutoff > current.cutoff) setControl(canonical, c.until, c.cutoff)
        }
    }
    fun control(identity: String): ManualControl? = readableDatabase.rawQuery("SELECT until_ts,cutoff FROM manual WHERE identity=?", arrayOf(identity)).use { if (it.moveToFirst()) ManualControl(it.getLong(0), it.getLong(1)) else null }
    @Synchronized fun setControl(identity: String, until: Long, cutoff: Long) {
        writableDatabase.insertWithOnConflict("manual", null, ContentValues().apply { put("identity", identity); put("until_ts", until); put("cutoff", cutoff) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun diagnostics(): String {
        val lines = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT state,COUNT(*),MIN(created) FROM jobs WHERE state IN ('queued','running','sending','failed','uncertain') GROUP BY state", null).use { c ->
            while (c.moveToNext()) lines.add("${stateLabel(c.getString(0))}: ${c.getInt(1)} · старейшее ${(System.currentTimeMillis()-c.getLong(2))/60000} мин")
        }
        readableDatabase.rawQuery("SELECT state,detail,created FROM outgoing ORDER BY created DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) lines.add("Последняя отправка: ${stateLabel(c.getString(0))} · ${c.getString(1)}")
        }
        readableDatabase.rawQuery("SELECT state,created FROM outgoing WHERE state IN ('sent','delivered','observed') ORDER BY created DESC LIMIT 1", null).use { c ->
            if(c.moveToFirst()) lines.add("Последнее подтверждение: " + stateLabel(c.getString(0)) + " · " + java.text.SimpleDateFormat("dd.MM HH:mm",java.util.Locale.getDefault()).format(java.util.Date(c.getLong(1))))
        }
        return lines.joinToString("\n").ifBlank { "Очередь пуста" }
    }
    fun outgoingFor(identity: String): List<String> = readableDatabase.rawQuery(
        "SELECT created,state,detail FROM outgoing WHERE identity=? ORDER BY created DESC LIMIT 5", arrayOf(identity)).use { c ->
        buildList { while (c.moveToNext()) add(java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(c.getLong(0))) + " · " + stateLabel(c.getString(1)) + c.getString(2).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()) }
    }
    companion object {
        @Volatile private var instance: RuntimeDb? = null
        fun get(context: Context): RuntimeDb = instance ?: synchronized(this) { instance ?: RuntimeDb(context.applicationContext).also { instance = it } }
        fun stateLabel(s: String): String = when (s) {
            "queued" -> "Ожидает"; "running" -> "Готовится"; "sending", "submitted" -> "Передано на отправку"
            "sent" -> "Отправлено"; "delivered" -> "Доставлено"; "observed" -> "Исходящее найдено в переписке"
            "failed" -> "Ошибка"; "uncertain" -> "Результат неизвестен"; "expired" -> "Устарело"; else -> s
        }
    }
}
