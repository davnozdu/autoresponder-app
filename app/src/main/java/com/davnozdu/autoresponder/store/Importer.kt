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

    /** Имя контакта кэшируем на время импорта: запрос к книге на каждую строку делал импорт
     *  нескольких тысяч SMS многоминутным. */
    private val nameCache = HashMap<String, String?>()
    /** Ветки человека на время импорта — по той же причине, что и имена: PersonThreads
     *  на каждый промах перебирает все ключи БД. */
    private val threadCache = HashMap<String, List<String>>()
    private fun nameFor(context: Context, num: String): String? =
        nameCache.getOrPut(num) { ContactUtil.nameFor(context, num) }

    /** @return число импортированных записей. */
    fun importAll(context: Context): Int {
        val app = context.applicationContext
        val db = HistoryDb.get(app)
        nameCache.clear()
        // Одна транзакция на весь импорт вместо отдельной на каждую вставку.
        return db.inTransaction {
            importSms(app, db) + importCalls(app, db)
        }.also { nameCache.clear(); threadCache.clear() }
    }

    /**
     * Добор свежих SMS из системной базы — то, что робот не отправлял сам.
     *
     * Раньше исходящие попадали в журнал только через кнопку «Импорт», то есть один раз
     * за всё время. Всё, что владелец писал клиенту руками, для LLM не существовало: она
     * видела подряд «Client: …, Client: …» и отвечала так, будто разговора не было —
     * клиент отвечает на вчерашнее сообщение, а робот здоровается заново.
     *
     * @param since брать записи новее этого момента (мс).
     * @return число добавленных записей.
     */
    fun importSmsSince(context: Context, since: Long): Int {
        val app = context.applicationContext
        val db = HistoryDb.get(app)
        return db.inTransaction { importSms(app, db, since) }
            .also { nameCache.clear(); threadCache.clear() }
    }

    private fun importSms(context: Context, db: HistoryDb, since: Long = 0L): Int {
        var n = 0
        val aiPrefix = com.davnozdu.autoresponder.data.Settings(context).aiPrefix.trim()
        val uri = Uri.parse("content://sms")
        val sel = if (since > 0) "date>?" else null
        val args = if (since > 0) arrayOf(since.toString()) else null
        context.contentResolver.query(uri,
            arrayOf("address", "body", "date", "type"), sel, args, "date DESC")?.use { c ->
            val iA = c.getColumnIndex("address"); val iB = c.getColumnIndex("body")
            val iD = c.getColumnIndex("date"); val iT = c.getColumnIndex("type")
            while (c.moveToNext()) {
                val num = realNumber(c.getString(iA)) ?: continue
                val ts = c.getLong(iD)
                val dir = if (c.getInt(iT) == 2) "out" else "in"
                val body = c.getString(iB) ?: ""
                if (db.existsAt(num, ts, dir)) continue
                // Наши собственные ответы система тоже кладёт в content://sms, но со своей
                // отметкой времени — по точному совпадению их не отсечь, сверяем по тексту.
                val keys = threadCache.getOrPut(num) { PersonThreads.keysFor(context, num) }
                if (db.existsNear(keys, "sms", dir, body, ts)) continue
                val name = nameFor(context, num)
                // Свои же ответы (ушли через SmsManager, но лежат и в системной базе)
                // помечаем как авто — иначе «Требуют ответа» примет их за живой ответ.
                val auto = dir == "out" && aiPrefix.isNotEmpty() &&
                    body.startsWith(aiPrefix, ignoreCase = true)
                db.insert(num, name, "sms", dir, body, ts, auto = auto)
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
                val name = nameFor(context, num)
                db.insert(num, name, "call", dir, label, ts)
                n++
            }
        }
        return n
    }
}
