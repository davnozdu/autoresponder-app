package com.davnozdu.autoresponder.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.PhoneMask
import java.io.File

/**
 * Импорт переписки WhatsApp из копии `msgstore.db`, которую кладёт root-мост модуля.
 *
 * Слушатель уведомлений видит только то, что показала система: входящее, пока робот включён,
 * не старше пяти минут и с кнопкой «Ответить». Всё, что владелец написал клиенту руками,
 * не порождает уведомления вообще — и в контекст LLM уходил односторонний монолог клиента.
 * Здесь берём обе стороны прямо из базы мессенджера.
 *
 * Схема простая: `message.text_data` + `from_me` + `timestamp`, чат через `chat.jid_row_id`
 * в `jid`. Единственная тонкость — LID: новый WhatsApp часть чатов держит под скрытой
 * идентичностью (`jid.server='lid'`), настоящий номер лежит через `jid_map`. Без этого
 * сопоставления 153 чата из 491 пришли бы без номера.
 */
object WaImporter {

    /** [added] — число новых записей, [lastTs] — timestamp последней строки, реально
     *  просмотренной в этом проходе (-1, если проход не вернул ни одной строки: тогда
     *  advance по mtime копии безопасен, см. [MsgrBridge.importOne]). LIMIT в [SQL] режет
     *  проход на 4000 строк — при большой первичной истории [lastTs] может остаться
     *  посреди backlog, и следующий вызов должен продолжить именно с него, а не
     *  перепрыгивать сразу на mtime (иначе остаток backlog терялся бы навсегда). */
    data class Result(val added: Int, val lastTs: Long)

    /** Глубина первого импорта. Дальше берём только новее водяного знака. */
    private const val FIRST_RUN_DAYS = 120L

    /**
     * `message_type IN (0, 1, 3, 57)` — типы, у которых `text_data` действительно текст
     * разговора: 0 — обычное сообщение, 1 и 3 — подпись к фото и видео, 57 — деловое.
     *
     * Список именно разрешительный, потому что `text_data` заполнен и у того, что текстом
     * не является: у типа 9 (документ) там имя файла («fakt545.pdf»), у 4 (визитка) — имя
     * контакта, у 7 (служебное) — литерал «true». В журнале нужен текст, а не вложения:
     * сама картинка в контекст всё равно не попадёт, а строка «fakt545.pdf» только
     * вытеснит из окна LLM настоящую реплику клиента.
     *
     * `jid_map` — про LID: новый WhatsApp часть чатов держит под скрытой идентичностью
     * (`jid.server='lid'`), и настоящий номер лежит через это сопоставление. Без него
     * 153 чата из 491 пришли бы без номера.
     */
    private const val SQL = """
        SELECT COALESCE(rj.user, j.user) AS num,
               m.from_me AS fm,
               m.timestamp AS ts,
               m.text_data AS body
        FROM message m
          JOIN chat c ON c._id = m.chat_row_id
          JOIN jid j  ON j._id = c.jid_row_id
          LEFT JOIN jid_map jm ON jm.lid_row_id = j._id
          LEFT JOIN jid rj     ON rj._id = jm.jid_row_id
        WHERE j.server IN ('s.whatsapp.net', 'lid')
          AND m.message_type IN (0, 1, 3, 57)
          AND m.text_data IS NOT NULL AND m.text_data <> ''
          AND m.timestamp > ?
        ORDER BY m.timestamp
        LIMIT 4000
    """

    /** @return [Result]; added=-1 если базы нет или её не открыть. */
    fun import(context: Context, dbFile: File, channel: String, since: Long): Result {
        if (!dbFile.exists()) return Result(-1, -1)
        val prefix = com.davnozdu.autoresponder.data.Settings(context).aiPrefix.trim()
        val log = EventLog(context)
        val db = try {
            // Открываем на запись: в копии остался неприменённый -wal, и без права
            // записи SQLite не сможет его накатить — свежие сообщения не увидим.
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            log.add("WA импорт: база не открылась (${e.message})"); return Result(-1, -1)
        }
        val from = if (since > 0) since else
            System.currentTimeMillis() - FIRST_RUN_DAYS * 86_400_000L
        var added = 0
        var rows = 0
        var lastTs = -1L
        // Имя контакта кэшируем на время импорта: запрос к книге на каждую из тысяч строк
        // растягивал бы импорт на минуты (та же причина, что в [Importer]).
        val names = HashMap<String, String?>()
        // И ветки человека: PersonThreads на каждый промах кэша перебирает все ключи БД,
        // а в импорте строк тысячи — без памятки это скан таблицы на каждое сообщение.
        val threads = HashMap<String, List<String>>()
        try {
            val hist = HistoryDb.get(context)
            hist.inTransaction {
                db.rawQuery(SQL, arrayOf(from.toString())).use { c ->
                    val iNum = c.getColumnIndexOrThrow("num")
                    val iFm = c.getColumnIndexOrThrow("fm")
                    val iTs = c.getColumnIndexOrThrow("ts")
                    val iBody = c.getColumnIndexOrThrow("body")
                    while (c.moveToNext()) {
                        rows++
                        // ts — ДО любых continue: SQL сортирует по timestamp, так что ts
                        // последней просмотренной строки (даже пропущенной) — верная граница
                        // прохода для watermark, независимо от того, была ли строка вставлена.
                        val ts = c.getLong(iTs)
                        lastTs = ts
                        val raw = c.getString(iNum) ?: continue
                        val body = c.getString(iBody)?.trim().orEmpty()
                        if (body.isEmpty()) continue
                        val dir = if (c.getInt(iFm) == 1) "out" else "in"
                        // jid.user — цифры без «+»; приводим к тому же виду, что SMS и звонки,
                        // иначе PersonThreads не склеит ветки одного человека.
                        val num = PhoneMask.canonical(raw)
                        if (!PhoneMask.looksLikeNumber(num)) continue
                        if (dir == "out") com.davnozdu.autoresponder.respond.Outgoing.observed(context, num, channel, body, ts)
                        if (hist.existsAt(num, ts, dir)) continue
                        val keys = threads.getOrPut(num) { PersonThreads.keysFor(context, num) }
                        // Дубль ищем только среди ЧУЖИХ ключей — то есть в записях пути
                        // уведомлений. Свои прошлые строки (под тем же номером) исключены:
                        // два одинаковых коротких сообщения подряд — обычное дело.
                        if (hist.existsNear(keys, channel, dir, body, ts,
                                HistoryDb.MSGR_WINDOW_MS, excludeKey = num)) continue
                        val name = names.getOrPut(num) { ContactUtil.nameFor(context, num) }
                        // Робот свои ответы шлёт с префиксом ИИ. Без этой пометки экран
                        // «Требуют ответа» счёл бы авто-ответ живым ответом и убрал ветку
                        // из списка — обещание «ответим в рабочее время» осталось бы ничьим.
                        val auto = dir == "out" && prefix.isNotEmpty() &&
                            body.startsWith(prefix, ignoreCase = true)
                        hist.insert(num, name, channel, dir, body, ts, auto = auto)
                        added++
                    }
                }
                added
            }
        } catch (e: Exception) {
            log.add("WA импорт: ошибка (${e.message})")
        } finally {
            try { db.close() } catch (_: Exception) {}
        }
        if (added > 0) PersonThreads.invalidate()
        return Result(added, if (rows > 0) lastTs else -1)
    }
}
