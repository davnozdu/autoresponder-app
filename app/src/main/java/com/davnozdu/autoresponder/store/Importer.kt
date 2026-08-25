package com.davnozdu.autoresponder.store

import android.content.Context
import android.net.Uri
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.PhoneMask

/** Импорт существующих SMS и журнала звонков из системных провайдеров в нашу БД. */
object Importer {

    private fun realNumber(raw: String?): String? {
        if (raw.isNullOrBlank() || PhoneMask.isAlphanumericSender(raw)) return null
        if (raw.count { it.isDigit() } < 8) return null
        return PhoneMask.normalize(raw) ?: raw
    }

    /** @return число импортированных записей. */
    fun importAll(context: Context): Int {
        val app = context.applicationContext
        val db = HistoryDb.get(app)
        var n = 0
        n += importSms(app, db)
        n += importCalls(app, db)
        return n
    }

    private fun importSms(context: Context, db: HistoryDb): Int {
        var n = 0
        val uri = Uri.parse("content://sms")
        context.contentResolver.query(uri,
            arrayOf("address", "body", "date", "type"), null, null, "date DESC")?.use { c ->
            val iA = c.getColumnIndex("address"); val iB = c.getColumnIndex("body")
            val iD = c.getColumnIndex("date"); val iT = c.getColumnIndex("type")
            while (c.moveToNext()) {
                val num = realNumber(c.getString(iA)) ?: continue
                val ts = c.getLong(iD)
                val dir = if (c.getInt(iT) == 2) "out" else "in"
                if (db.existsAt(num, ts, dir)) continue
                val name = ContactUtil.nameFor(context, num)
                db.insert(num, name, "sms", dir, c.getString(iB) ?: "", ts)
                n++
            }
        }
        return n
    }

    private fun importCalls(context: Context, db: HistoryDb): Int {
        var n = 0
        val uri = Uri.parse("content://call_log/calls")
        context.contentResolver.query(uri,
            arrayOf("number", "type", "date", "duration"), null, null, "date DESC")?.use { c ->
            val iN = c.getColumnIndex("number"); val iT = c.getColumnIndex("type")
            val iD = c.getColumnIndex("date"); val iDur = c.getColumnIndex("duration")
            while (c.moveToNext()) {
                val num = realNumber(c.getString(iN)) ?: continue
                val ts = c.getLong(iD)
                val type = c.getInt(iT)
                val dir = if (type == 2) "out" else "in"
                if (db.existsAt(num, ts, dir)) continue
                val label = when (type) {
                    1 -> "входящий звонок"; 2 -> "исходящий звонок"; 3 -> "пропущенный звонок"
                    5 -> "отклонённый звонок"; 6 -> "заблокированный звонок"; else -> "звонок"
                } + " (${c.getLong(iDur)}с)"
                val name = ContactUtil.nameFor(context, num)
                db.insert(num, name, "call", dir, label, ts)
                n++
            }
        }
        return n
    }
}
