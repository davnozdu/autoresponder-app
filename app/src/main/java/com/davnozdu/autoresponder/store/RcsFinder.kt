package com.davnozdu.autoresponder.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.rules.PhoneMask
import java.io.File

/**
 * Номер отправителя RCS из копии базы Google Messages, которую кладёт root-мост модуля.
 *
 * Последний источник в цепочке [com.davnozdu.autoresponder.rules.SenderNumber]: нужен ровно
 * тогда, когда незнакомец пишет по RCS под своим профилем. В уведомлении в этом случае имя и
 * аватар, которые он задал сам, а номера нет нигде — ни в строке отправителя, ни в книге
 * контактов. Сам мессенджер номер, разумеется, знает: сообщение лежит у него в `bugle_db`
 * вместе с участником беседы.
 *
 * Ищем по содержимому: номер сообщения в базе нам неизвестен, зато известен его текст и
 * время показа уведомления. Совпадение текста в пределах окна — это то же самое сообщение;
 * два разных человека с точностью до символа в одну минуту не пишут.
 *
 * Базы под чужим uid с правами 0600 — приложению их не открыть, копирование делает модуль
 * (`bridge.sh`). Модуля может не быть или он может быть старым: тогда копии нет, метод
 * возвращает `null`, и сообщение просто пропускается, как и раньше.
 */
object RcsFinder {

    /** Расхождение времени сообщения в базе и момента показа уведомления. */
    private const val WINDOW_MS = 10 * 60_000L

    private fun dbFile(context: Context) =
        File(context.filesDir, "bridge/rcs/bugle_db")

    /**
     * `sender_send_destination` заполнен не у всех сообщений, поэтому подстраховываемся
     * участником беседы. Своих сообщений ищем не боясь: текст входящего с ними не совпадёт.
     */
    private const val SQL = """
        SELECT COALESCE(NULLIF(TRIM(m.sender_send_destination), ''),
                        p.normalized_destination) AS num
        FROM messages m
          JOIN parts pa ON pa.message_id = m._id
          LEFT JOIN participants p ON p._id = m.sender_id
        WHERE TRIM(pa.text) = ?
          AND m.received_timestamp BETWEEN ? AND ?
        ORDER BY m.received_timestamp DESC
        LIMIT 16
    """

    /**
     * @param text текст входящего ровно в том виде, в каком он пришёл в уведомлении
     * @param ts   время сообщения (отметка из уведомления)
     * @return номер в каноническом виде или `null`
     */
    fun numberFor(context: Context, text: String, ts: Long): String? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val app = context.applicationContext
        val f = dbFile(app)
        if (!f.exists()) return null
        // Сначала смотрим в том, что уже лежит: модуль обновляет копии сам, и чаще всего
        // сообщение там уже есть.
        lookup(app, f, body, ts)?.let { return it }
        // Нет — копия могла отстать. Просим модуль обновить и смотрим ещё раз.
        //
        // Не на каждый промах: сюда попадают и рассылки («CeskaPosta», «Allegro»), у которых
        // номера нет в принципе. Без ограничения каждая из них заставляла бы ждать модуль,
        // а копировать он должен из-за настоящего клиента, не из-за трекинга посылки.
        if (f.lastModified() >= ts) return null
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastRefresh < REFRESH_GAP_MS) return null
            lastRefresh = now
        }
        MsgrBridge.refresh(app)
        val fresh = dbFile(app)
        return if (fresh.exists()) lookup(app, fresh, body, ts) else null
    }

    /** Чаще этого модуль из-за одного ненайденного отправителя не тревожим. */
    private const val REFRESH_GAP_MS = 30_000L
    @Volatile private var lastRefresh = 0L

    private fun lookup(app: Context, f: File, body: String, ts: Long): String? {
        val db = try {
            // На запись — иначе SQLite не накатит неприменённый -wal, а свежее сообщение
            // лежит именно там (та же причина, что в [WaImporter]).
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            EventLog(app).add("RCS: база Messages не открылась (${e.message})"); return null
        }
        return try {
            db.rawQuery(SQL, arrayOf(body, (ts - WINDOW_MS).toString(), (ts + WINDOW_MS).toString()))
                .use { c ->
                    // Одинаковый текст в одном окне может принадлежать нескольким людям.
                    // Возвращаем номер только при единственном кандидате.
                    val candidates = LinkedHashSet<String>()
                    while (c.moveToNext()) {
                        PhoneMask.canonical(c.getString(0)?.trim())
                            .takeIf { PhoneMask.looksLikeNumber(it) }
                            ?.let { candidates.add(it) }
                        if (candidates.size > 1) return@use null
                    }
                    candidates.singleOrNull()
                }
        } catch (e: Exception) {
            // Схему Google Messages меняет без предупреждения — это не повод падать.
            EventLog(app).add("RCS: разбор базы Messages не удался (${e.message})"); null
        } finally {
            try { db.close() } catch (_: Exception) {}
        }
    }
}
