package com.davnozdu.autoresponder.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistItem(
    val id: Long, val number: String, val name: String?,
    val channel: String, val direction: String, val body: String, val ts: Long
)

/** Локальная история сообщений/SMS/звонков по номеру (+имя из книги). */
class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun insert(number: String, name: String?, channel: String, direction: String, body: String, ts: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("number", number); put("name", name); put("channel", channel)
            put("direction", direction); put("body", body); put("ts", ts)
        }
        writableDatabase.insert("events", null, cv)
    }

    /** Различные ветки (по номеру), с последним сообщением — для списка/поиска. */
    fun conversations(query: String, limit: Int = 100): List<HistItem> {
        val res = ArrayList<HistItem>()
        val like = "%${query.trim()}%"
        val sql = "SELECT e.* FROM events e JOIN (" +
            "SELECT number, MAX(ts) mts FROM events GROUP BY number) m " +
            "ON e.number=m.number AND e.ts=m.mts " +
            (if (query.isBlank()) "" else "WHERE e.number LIKE ? OR e.name LIKE ? ") +
            "ORDER BY e.ts DESC LIMIT $limit"
        val args = if (query.isBlank()) emptyArray() else arrayOf(like, like)
        readableDatabase.rawQuery(sql, args).use { c ->
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

    companion object {
        @Volatile private var inst: HistoryDb? = null
        fun get(context: Context): HistoryDb =
            inst ?: synchronized(this) { inst ?: HistoryDb(context).also { inst = it } }
    }
}
