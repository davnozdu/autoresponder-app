package com.davnozdu.autoresponder.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ReplyJob(val id: Long, val who: String, val kind: String, val payload: String,
                    val created: Long, val expires: Long)
data class ManualControl(val until: Long, val cutoff: Long)

/** Small durable event journal. Messenger snapshots remain disposable tmpfs files. */
class RuntimeDb internal constructor(context: Context, name: String = "runtime.db") : SQLiteOpenHelper(context, name, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT UNIQUE, who TEXT, kind TEXT, payload TEXT, created INTEGER, expires INTEGER, state TEXT, detail TEXT DEFAULT '', available INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX jobs_state ON jobs(state,id)")
        // Чистка и выборка идут по created, а индекса не было вовсе: каждый enqueue делал три
        // полных скана на самом горячем пути (каждое входящее сообщение).
        db.execSQL("CREATE INDEX jobs_created ON jobs(created)")
        db.execSQL("CREATE TABLE manual(identity TEXT PRIMARY KEY, until_ts INTEGER, cutoff INTEGER)")
        db.execSQL("CREATE TABLE aliases(identity TEXT PRIMARY KEY, canonical TEXT)")
        db.execSQL("CREATE TABLE outgoing(id TEXT PRIMARY KEY, identity TEXT, channel TEXT, body TEXT, created INTEGER, state TEXT, detail TEXT, parts INTEGER, job INTEGER, history_key TEXT, limit_key TEXT, timeout INTEGER, committed INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX outgoing_created ON outgoing(created)")
        db.execSQL("CREATE TABLE segments(outgoing TEXT, part INTEGER, sent INTEGER, delivered INTEGER, PRIMARY KEY(outgoing,part))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS jobs_created ON jobs(created)")
            db.execSQL("CREATE INDEX IF NOT EXISTS outgoing_created ON outgoing(created)")
        }
    }

    /**
     * Чистка старых метаданных — не чаще раза в час.
     *
     * Раньше три DELETE выполнялись в каждом enqueue, то есть на каждое входящее сообщение, и
     * все три были полными сканами (индексов по created не было). Сама работа нужна раз в
     * сутки; к горячему пути она отношения не имеет.
     */
    @Volatile private var lastPruneAt = 0L
    private fun pruneIfDue(now: Long) {
        if (now - lastPruneAt < 60 * 60_000L) return
        lastPruneAt = now
        runCatching {
            writableDatabase.delete("jobs", "created < ? AND state NOT IN ('queued','running','sending')", arrayOf((now - 7 * 86_400_000L).toString()))
            // Задания, застрявшие в «передано на отправку», чей срок истёк больше часа назад:
            // процесс, который их вёл, умер (перезагрузка, LMK), и подтверждения уже не будет.
            // Час запаса — чтобы не тронуть задание, которое прямо сейчас отправляется.
            // На телефоне так висели 4 задания от 26–28.10 в состоянии «Передано на отправку».
            writableDatabase.execSQL(
                "UPDATE jobs SET state='uncertain',detail='Результат неизвестен: приложение закрылось до подтверждения' " +
                    "WHERE state IN ('sending','submitted') AND expires < ?",
                arrayOf(now - 60 * 60_000L))
            writableDatabase.execSQL("DELETE FROM segments WHERE outgoing IN (SELECT id FROM outgoing WHERE created < ?)", arrayOf(now - 30 * 86_400_000L))
            writableDatabase.delete("outgoing", "created < ?", arrayOf((now - 30 * 86_400_000L).toString()))
        }
    }

    @Synchronized fun enqueue(token: String, who: String, kind: String, payload: String, ttl: Long, availableInMs: Long = 0): Long {
        val now = System.currentTimeMillis()
        // Prune terminal metadata only. Never evict work to make room for new events.
        pruneIfDue(now)
        if (availableInMs > 0) {
            // Сборка сообщений в одну реплику: новое сообщение от того же адресата отменяет
            // более раннее ещё не начатое ожидание (SMS/мессенджер) — отвечаем один раз на
            // всё, что пришло за окно тишины [Settings.batchWaitMs], а не на каждое сообщение
            // отдельно. Уже начатую (running/sending) обработку не трогаем — она либо уже
            // отправляет ответ, либо вот-вот отправит; новое сообщение просто заведёт свой
            // отдельный батч.
            writableDatabase.execSQL(
                "UPDATE jobs SET state='superseded',detail='Объединено со следующим сообщением' " +
                    "WHERE who=? AND kind IN ('SMS','notification') AND state='queued'", arrayOf(who))
        }
        return writableDatabase.insertWithOnConflict("jobs", null, ContentValues().apply {
            put("token", token); put("who", who); put("kind", kind); put("payload", payload)
            put("created", now); put("expires", now + ttl); put("state", "queued")
            put("available", now + availableInMs)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun recover() {
        writableDatabase.execSQL("UPDATE jobs SET state='queued' WHERE state='running'")
        // A crash between an external side effect and its acknowledgement is ambiguous.
        // Never replay it automatically: that could send a second reply or CRM action.
        writableDatabase.execSQL("UPDATE jobs SET state='uncertain', detail='Перезапуск во время отправки: автоматического повтора нет' WHERE state IN ('sending','external')")
    }
    @Synchronized fun next(excluded: Set<String>, notificationsReady: Boolean): ReplyJob? {
        val now = System.currentTimeMillis()
        // Истечение раньше оседало только здесь, и в журнале событий сообщение клиента просто
        // обрывалось — без причины (так выглядело голосовое 25.10). Пишем каждое.
        readableDatabase.rawQuery("SELECT id,kind,payload FROM jobs WHERE state='queued' AND expires < ?", arrayOf(now.toString())).use { c ->
            while (c.moveToNext()) {
                val who = try { org.json.JSONObject(c.getString(2)).let { it.optString("sender").ifBlank { it.optString("number") } } }
                          catch (_: Exception) { "" }
                com.davnozdu.autoresponder.data.EventLog().add("QUEUE #${c.getLong(0)} (${c.getString(1)}) ${who.take(16)} — истёк, так и не начав обрабатываться, ответа нет")
            }
        }
        writableDatabase.execSQL("UPDATE jobs SET state='expired',detail='Истёк срок актуальности' WHERE state='queued' AND expires < ?", arrayOf(now))
        val seen=excluded.toMutableSet()
        // Сначала выбираем кандидата и закрываем курсор, и только потом пишем в ту же таблицу:
        // state() внутри открытого курсора по jobs — это UPDATE той же таблицы на том же
        // соединении, что SQLite терпит, но делает выборку неопределённой.
        var picked: ReplyJob? = null
        readableDatabase.rawQuery("SELECT id,who,kind,payload,created,expires,available FROM jobs WHERE state='queued' ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val identity = canonical(c.getString(1))
                if (!seen.add(identity) || c.getLong(6)>now || (c.getString(2) == "notification" && !notificationsReady)) continue
                picked = ReplyJob(c.getLong(0), identity, c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))
                break
            }
        }
        return picked?.also { state(it.id, "running") }
    }
    @Synchronized fun state(id: Long, state: String, detail: String = "") {
        if (id <= 0) return
        writableDatabase.update("jobs", ContentValues().apply { put("state", state); put("detail", detail) }, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun finish(id: Long) {
        // Задание считается закрытым, из какого бы транспорта оно ни вернулось: SmsSender и
        // Outgoing уводят его в sending/submitted/sent, и прежнее условие (running/external)
        // оставляло отвеченные SMS в подвешенном состоянии — на телефоне таких висело 4.
        writableDatabase.execSQL(
            "UPDATE jobs SET state='done',detail=CASE WHEN detail='' THEN 'Обработано по правилам' ELSE detail END " +
                "WHERE id=? AND state NOT IN ('done','expired','superseded','failed','uncertain')", arrayOf(id))
    }
    @Synchronized fun defer(id: Long, millis: Long) {
        writableDatabase.execSQL("UPDATE jobs SET state='queued',detail='Ожидает подтверждения предыдущей SMS',available=? WHERE id=?", arrayOf(System.currentTimeMillis()+millis,id))
    }
    fun nextDelay(): Long = readableDatabase.rawQuery("SELECT MIN(available) FROM jobs WHERE state='queued' AND available>?", arrayOf(System.currentTimeMillis().toString())).use {
        if(it.moveToFirst() && !it.isNull(0)) (it.getLong(0)-System.currentTimeMillis()).coerceAtLeast(1) else 0
    }
    fun valid(id: Long): Boolean {
        if (id <= 0) return true
        readableDatabase.rawQuery("SELECT expires FROM jobs WHERE id=?", arrayOf(id.toString())).use { return it.moveToFirst() && it.getLong(0) >= System.currentTimeMillis() }
    }
    /**
     * Кэш псевдонимов «ветка → каноническая ветка».
     *
     * canonical() вызывался на каждую строку выборки в next() и на каждую итерацию цикла
     * EventQueue.kick — то есть десятки отдельных запросов на одну диспетчеризацию. Таблица
     * крошечная и меняется только при bind(), поэтому читаем её один раз.
     */
    @Volatile private var aliasCache: Map<String, String>? = null
    private fun aliasMap(): Map<String, String> {
        aliasCache?.let { return it }
        val m = HashMap<String, String>()
        runCatching {
            readableDatabase.rawQuery("SELECT identity, canonical FROM aliases", null).use { c ->
                while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
            }
        }
        aliasCache = m
        return m
    }
    fun canonical(identity: String): String = aliasMap()[identity] ?: identity
    @Synchronized fun bind(identity: String, canonical: String) {
        if (identity == canonical) return
        aliasCache = null
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
        /** Схема: 2 — индексы по created (jobs, outgoing). Старые базы до-мигрируют в onUpgrade. */
        private const val DB_VERSION = 2
        @Volatile private var instance: RuntimeDb? = null
        fun get(context: Context): RuntimeDb = instance ?: synchronized(this) { instance ?: RuntimeDb(context.applicationContext).also { instance = it } }
        fun stateLabel(s: String): String = when (s) {
            "queued" -> "Ожидает"; "running" -> "Готовится"; "sending", "submitted" -> "Передано на отправку"
            "sent" -> "Отправлено"; "delivered" -> "Доставлено"; "observed" -> "Исходящее найдено в переписке"
            "failed" -> "Ошибка"; "uncertain" -> "Результат неизвестен"; "expired" -> "Устарело"; else -> s
        }
    }
}
