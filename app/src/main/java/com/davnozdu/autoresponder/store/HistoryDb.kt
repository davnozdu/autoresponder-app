package com.davnozdu.autoresponder.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BlackEntry(
    val id: Long, val identity: String, val name: String?,
    val viaLlm: Boolean, val prompt: String?,
    val onSms: Boolean = true, val onMsgr: Boolean = true,
    val onCalls: Boolean = true, val callPrompt: String? = null
)

data class HistItem(
    val id: Long, val number: String, val name: String?,
    val channel: String, val direction: String, val body: String, val ts: Long,
    val auto: Boolean = false
)

/** Локальная история сообщений/SMS/звонков по номеру (+имя из книги). */
class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "number TEXT NOT NULL," +
                "name TEXT," +
                "channel TEXT NOT NULL," +      // sms|rcs|whatsapp|telegram|call
                "direction TEXT NOT NULL," +    // in|out
                "body TEXT," +
                "ts INTEGER NOT NULL," +
                "auto INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_num ON events(number)")
        db.execSQL("CREATE INDEX idx_ts ON events(ts)")
        createBlacklist(db)
        createQa(db)
    }

    private fun createQa(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE qa(_id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    }

    private fun createBlacklist(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE blacklist(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "identity TEXT NOT NULL," +   // номер или имя
                "name TEXT," +
                "via_llm INTEGER NOT NULL DEFAULT 0," +
                "prompt TEXT," +
                "on_sms INTEGER NOT NULL DEFAULT 1," +
                "on_msgr INTEGER NOT NULL DEFAULT 1," +
                "on_calls INTEGER NOT NULL DEFAULT 1," +
                "call_prompt TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) createBlacklist(db)
        if (oldV < 3) createQa(db)
        if (oldV < 5) db.execSQL("ALTER TABLE events ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
        if (oldV < 4) {
            db.execSQL("ALTER TABLE blacklist ADD COLUMN on_sms INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE blacklist ADD COLUMN on_msgr INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE blacklist ADD COLUMN on_calls INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE blacklist ADD COLUMN call_prompt TEXT")
        }
    }

    fun insert(number: String, name: String?, channel: String, direction: String, body: String, ts: Long = System.currentTimeMillis(), auto: Boolean = false) {
        val cv = ContentValues().apply {
            put("number", number); put("name", name); put("channel", channel)
            put("direction", direction); put("body", body); put("ts", ts); put("auto", if (auto) 1 else 0)
        }
        writableDatabase.insert("events", null, cv)
    }

    fun clearEvents() { writableDatabase.delete("events", null, null) }

    fun existsAt(number: String, ts: Long, direction: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM events WHERE number=? AND ts=? AND direction=? LIMIT 1",
            arrayOf(number, ts.toString(), direction)
        ).use { c -> return c.moveToFirst() }
    }

    /** Различные ветки (по номеру), с последним сообщением — для списка/поиска. */
    fun conversations(query: String, channels: List<String> = emptyList(), autoOnly: Boolean = false, limit: Int = 100): List<HistItem> {
        val res = ArrayList<HistItem>()
        val like = "%${query.trim()}%"
        val chFilter = if (channels.isEmpty()) "" else
            "WHERE channel IN (${channels.joinToString(",") { "'" + it + "'" }}) "
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (query.isNotBlank()) { where.add("(e.number LIKE ? OR e.name LIKE ?)"); args.add(like); args.add(like) }
        if (channels.isNotEmpty()) where.add("e.channel IN (${channels.joinToString(",") { "'" + it + "'" }})")
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ") + " "
        val sql = "SELECT e.* FROM events e JOIN (" +
            "SELECT number, MAX(ts) mts FROM events $chFilter GROUP BY number" +
            (if (autoOnly) " HAVING SUM(auto)>0" else "") + ") m " +
            "ON e.number=m.number AND e.ts=m.mts " +
            whereSql +
            "ORDER BY e.ts DESC LIMIT $limit"
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) res.add(row(c))
        }
        return res
    }

    /** Ветка одного номера, опц. за период [from,to]. */
    fun thread(number: String, from: Long = 0, to: Long = Long.MAX_VALUE, limit: Int = 2000): List<HistItem> {
        val res = ArrayList<HistItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM events WHERE number=? AND ts BETWEEN ? AND ? ORDER BY ts ASC LIMIT $limit",
            arrayOf(number, from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) res.add(row(c)) }
        return res
    }

    /** Последние события (все каналы) для LLM-контекста. */
    fun recentEvents(limit: Int = 400): List<HistItem> {
        val res = ArrayList<HistItem>()
        readableDatabase.rawQuery("SELECT * FROM events ORDER BY ts DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) res.add(row(c))
        }
        return res
    }

    private fun row(c: android.database.Cursor): HistItem {
        val autoIdx = c.getColumnIndex("auto")
        return HistItem(
            c.getLong(0), c.getString(1), c.getStringOrNull(2),
            c.getString(3), c.getString(4), c.getStringOrNull(5) ?: "", c.getLong(6),
            autoIdx >= 0 && c.getInt(autoIdx) == 1)
    }
    private fun android.database.Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)

    // --- История запросов (Q&A чат) ---
    fun qaAll(): List<Pair<String, String>> {
        val res = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT role,text FROM qa ORDER BY _id ASC", null).use { c ->
            while (c.moveToNext()) res.add(c.getString(0) to c.getString(1))
        }
        return res
    }
    fun qaAdd(role: String, text: String) {
        val cv = ContentValues().apply { put("role", role); put("text", text); put("ts", System.currentTimeMillis()) }
        writableDatabase.insert("qa", null, cv)
    }
    fun qaClear() { writableDatabase.delete("qa", null, null) }

    // --- Чёрный список ---
    fun blacklistAll(): List<BlackEntry> {
        val res = ArrayList<BlackEntry>()
        readableDatabase.rawQuery("SELECT _id,identity,name,via_llm,prompt,on_sms,on_msgr,on_calls,call_prompt FROM blacklist ORDER BY _id DESC", null).use { c ->
            while (c.moveToNext()) res.add(BlackEntry(
                c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2),
                c.getInt(3) == 1, if (c.isNull(4)) null else c.getString(4),
                c.getInt(5) == 1, c.getInt(6) == 1, c.getInt(7) == 1,
                if (c.isNull(8)) null else c.getString(8)))
        }
        return res
    }
    fun blacklistUpsert(e: BlackEntry) {
        val cv = ContentValues().apply {
            put("identity", e.identity); put("name", e.name)
            put("via_llm", if (e.viaLlm) 1 else 0); put("prompt", e.prompt)
            put("on_sms", if (e.onSms) 1 else 0); put("on_msgr", if (e.onMsgr) 1 else 0)
            put("on_calls", if (e.onCalls) 1 else 0); put("call_prompt", e.callPrompt)
        }
        if (e.id > 0) writableDatabase.update("blacklist", cv, "_id=?", arrayOf(e.id.toString()))
        else writableDatabase.insert("blacklist", null, cv)
    }
    fun blacklistDelete(id: Long) { writableDatabase.delete("blacklist", "_id=?", arrayOf(id.toString())) }

    /** Совпадение по номеру (хвост цифр) или имени. */
    fun blacklistMatch(number: String?, name: String?): BlackEntry? {
        val numTail = number?.filter { it.isDigit() }?.takeLast(9)
        val nm = name?.trim()?.lowercase()
        for (e in blacklistAll()) {
            val eDigits = e.identity.filter { it.isDigit() }
            if (numTail != null && eDigits.length >= 8 && eDigits.takeLast(9) == numTail) return e
            if (nm != null && e.identity.trim().lowercase() == nm) return e
            if (nm != null && e.name?.trim()?.lowercase() == nm) return e
        }
        return null
    }

    companion object {
        @Volatile private var inst: HistoryDb? = null
        fun get(context: Context): HistoryDb =
            inst ?: synchronized(this) { inst ?: HistoryDb(context).also { inst = it } }
    }
}
