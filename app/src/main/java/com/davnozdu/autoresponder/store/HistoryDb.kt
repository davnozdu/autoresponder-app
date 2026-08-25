package com.davnozdu.autoresponder.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BlackEntry(
    val id: Long, val identity: String, val name: String?,
    val viaLlm: Boolean, val prompt: String?
)

data class HistItem(
    val id: Long, val number: String, val name: String?,
    val channel: String, val direction: String, val body: String, val ts: Long
)

/** Локальная история сообщений/SMS/звонков по номеру (+имя из книги). */
class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "number TEXT NOT NULL," +
                "name TEXT," +
                "channel TEXT NOT NULL," +      // sms|rcs|whatsapp|telegram|call
                "direction TEXT NOT NULL," +    // in|out
                "body TEXT," +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_num ON events(number)")
        db.execSQL("CREATE INDEX idx_ts ON events(ts)")
        createBlacklist(db)
    }

    private fun createBlacklist(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE blacklist(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "identity TEXT NOT NULL," +   // номер или имя
                "name TEXT," +
                "via_llm INTEGER NOT NULL DEFAULT 0," +
                "prompt TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) createBlacklist(db)
    }

    fun insert(number: String, name: String?, channel: String, direction: String, body: String, ts: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("number", number); put("name", name); put("channel", channel)
            put("direction", direction); put("body", body); put("ts", ts)
        }
        writableDatabase.insert("events", null, cv)
    }

    fun existsAt(number: String, ts: Long, direction: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM events WHERE number=? AND ts=? AND direction=? LIMIT 1",
            arrayOf(number, ts.toString(), direction)
        ).use { c -> return c.moveToFirst() }
    }

    /** Различные ветки (по номеру), с последним сообщением — для списка/поиска. */
    fun conversations(query: String, channels: List<String> = emptyList(), limit: Int = 100): List<HistItem> {
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
            "SELECT number, MAX(ts) mts FROM events $chFilter GROUP BY number) m " +
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

    private fun row(c: android.database.Cursor) = HistItem(
        c.getLong(0), c.getString(1), c.getStringOrNull(2),
        c.getString(3), c.getString(4), c.getStringOrNull(5) ?: "", c.getLong(6)
    )
    private fun android.database.Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)

    // --- Чёрный список ---
    fun blacklistAll(): List<BlackEntry> {
        val res = ArrayList<BlackEntry>()
        readableDatabase.rawQuery("SELECT _id,identity,name,via_llm,prompt FROM blacklist ORDER BY _id DESC", null).use { c ->
            while (c.moveToNext()) res.add(BlackEntry(
                c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2),
                c.getInt(3) == 1, if (c.isNull(4)) null else c.getString(4)))
        }
        return res
    }
    fun blacklistUpsert(e: BlackEntry) {
        val cv = ContentValues().apply {
            put("identity", e.identity); put("name", e.name)
            put("via_llm", if (e.viaLlm) 1 else 0); put("prompt", e.prompt)
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
