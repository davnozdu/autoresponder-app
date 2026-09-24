package com.davnozdu.autoresponder.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BlackEntry(
    val id: Long, val identity: String, val name: String?,
    val viaLlm: Boolean, val prompt: String?,
    val onSms: Boolean = true, val onMsgr: Boolean = true,
    val onCalls: Boolean = true, val callPrompt: String? = null,
    /** Момент, до которого действует запись; 0 — навсегда. */
    val untilTs: Long = 0L,
    /** Язык TTS для [callPrompt]: "" = авто (язык устройства — ненадёжно, см. Greeting.kt),
     *  иначе явный код ("cs","en","ru","uk"). Независим от общего приветствия — у каждого
     *  контакта в ЧС может быть свой язык. */
    val callPromptLang: String = ""
) {
    fun expired(now: Long = System.currentTimeMillis()): Boolean = untilTs in 1..now
}

/** Ветка, на которую клиент ждёт ответа живого человека. */
data class PendingItem(
    val number: String, val name: String?, val lastIn: Long,
    val channel: String, val body: String, val incoming: Int
)

data class HistItem(
    val id: Long, val number: String, val name: String?,
    val channel: String, val direction: String, val body: String, val ts: Long,
    val auto: Boolean = false
)

/** Запись голосового автоответчика: сообщение, наговорённое клиентом. */
data class AmRec(
    val id: Long, val number: String?, val name: String?,
    val ts: Long, val durationMs: Long, val file: String?,
    val reason: String?, val heard: Boolean
)

/** Локальная история сообщений/SMS/звонков по номеру (+имя из книги). */
class HistoryDb internal constructor(context: Context, name: String = "history.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS events(" +
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
        createBlPending(db)
        createSmsHold(db)
        createInboxDone(db)
        createAmRec(db)
    }

    /** Отметки «этой веткой я занялся» — чтобы разобранное не висело в списке вечно. */
    private fun createInboxDone(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS inbox_done(number TEXT PRIMARY KEY, ts INTEGER NOT NULL)")
    }

    /** Записи голосового автоответчика (сообщения клиентов). heard=0 — ещё не прослушано. */
    private fun createAmRec(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS am_rec(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "number TEXT," +
                "name TEXT," +
                "ts INTEGER NOT NULL," +          // время звонка
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "file TEXT," +                    // путь к скопированной записи
                "reason TEXT," +                  // blacklist|closed
                "heard INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_amrec_ts ON am_rec(ts)")
    }

    fun amRecInsert(number: String?, name: String?, ts: Long, durationMs: Long,
                    file: String?, reason: String?): Long {
        val v = ContentValues().apply {
            put("number", number); put("name", name); put("ts", ts)
            put("duration_ms", durationMs); put("file", file); put("reason", reason)
            put("heard", 0)
        }
        return writableDatabase.insert("am_rec", null, v)
    }

    /** Сколько записей ещё не прослушано — для счётчика в журнале/окне. */
    fun amRecNewCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM am_rec WHERE heard=0", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun amRecList(limit: Int = 200): List<AmRec> {
        val out = ArrayList<AmRec>()
        readableDatabase.rawQuery(
            "SELECT _id,number,name,ts,duration_ms,file,reason,heard FROM am_rec " +
                "ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(AmRec(
                    id = c.getLong(0), number = c.getString(1), name = c.getString(2),
                    ts = c.getLong(3), durationMs = c.getLong(4), file = c.getString(5),
                    reason = c.getString(6), heard = c.getInt(7) != 0))
            }
        }
        return out
    }

    fun amRecMarkHeard(id: Long) {
        writableDatabase.execSQL("UPDATE am_rec SET heard=1 WHERE _id=?", arrayOf(id))
    }

    fun amRecMarkAllHeard() {
        writableDatabase.execSQL("UPDATE am_rec SET heard=1 WHERE heard=0")
    }

    /** Привязать файл записи к строке (запись копируется после отбоя, позже вставки). */
    fun amRecSetFile(id: Long, file: String, durationMs: Long) {
        val v = ContentValues().apply { put("file", file); put("duration_ms", durationMs) }
        writableDatabase.update("am_rec", v, "_id=?", arrayOf(id.toString()))
    }

    /** Ночные авто-SMS, придержанные до утра (тихий час). */
    private fun createSmsHold(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_hold(_id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT, ts INTEGER)")
    }

    private fun createBlPending(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bl_pending(_id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT, name TEXT, channel TEXT, ts INTEGER)")
    }

    private fun createQa(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS qa(_id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    }

    private fun createBlacklist(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS blacklist(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "identity TEXT NOT NULL," +   // номер или имя
                "name TEXT," +
                "via_llm INTEGER NOT NULL DEFAULT 0," +
                "prompt TEXT," +
                "on_sms INTEGER NOT NULL DEFAULT 1," +
                "on_msgr INTEGER NOT NULL DEFAULT 1," +
                "on_calls INTEGER NOT NULL DEFAULT 1," +
                "call_prompt TEXT," +
                "call_prompt_lang TEXT," +
                "until_ts INTEGER NOT NULL DEFAULT 0)"
        )
    }

    /** ALTER, который не должен ронять апгрейд: колонка могла появиться другим путём. */
    private fun addColumn(db: SQLiteDatabase, sql: String) {
        try { db.execSQL(sql) } catch (_: Exception) { /* duplicate column — уже есть */ }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // Шаги строго по возрастанию версий. Важно: createBlacklist() создаёт таблицу СРАЗУ с
        // колонками v4, поэтому ALTER'ы ниже применимы только к БД, созданным на v2/v3 —
        // иначе апгрейд с v1 падал на «duplicate column name: on_sms».
        if (oldV < 2) createBlacklist(db)
        if (oldV < 3) createQa(db)
        if (oldV in 2..3) {
            addColumn(db, "ALTER TABLE blacklist ADD COLUMN on_sms INTEGER NOT NULL DEFAULT 1")
            addColumn(db, "ALTER TABLE blacklist ADD COLUMN on_msgr INTEGER NOT NULL DEFAULT 1")
            addColumn(db, "ALTER TABLE blacklist ADD COLUMN on_calls INTEGER NOT NULL DEFAULT 1")
            addColumn(db, "ALTER TABLE blacklist ADD COLUMN call_prompt TEXT")
        }
        if (oldV < 5) addColumn(db, "ALTER TABLE events ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
        if (oldV < 6) createBlPending(db)
        if (oldV < 7) addColumn(db, "ALTER TABLE blacklist ADD COLUMN until_ts INTEGER NOT NULL DEFAULT 0")
        if (oldV < 8) createSmsHold(db)
        if (oldV < 9) createInboxDone(db)
        if (oldV < 10) createAmRec(db)
        if (oldV < 11) addColumn(db, "ALTER TABLE blacklist ADD COLUMN call_prompt_lang TEXT")
    }

    /** Восстановление из бэкапа может подсунуть БД более старой схемы — не падаем, а до-мигрируем.
     *  По умолчанию SQLiteOpenHelper бросает SQLiteDowngradeFailedException и приложение крашится. */
    override fun onDowngrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        createBlacklist(db); createQa(db); createBlPending(db); createSmsHold(db); createInboxDone(db)
        // Таблица могла остаться из бэкапа старой схемы — CREATE IF NOT EXISTS её не тронет,
        // а SELECT с новыми колонками упадёт. ALTER'ы безопасны: дубликат глотается.
        addColumn(db, "ALTER TABLE blacklist ADD COLUMN on_sms INTEGER NOT NULL DEFAULT 1")
        addColumn(db, "ALTER TABLE blacklist ADD COLUMN on_msgr INTEGER NOT NULL DEFAULT 1")
        addColumn(db, "ALTER TABLE blacklist ADD COLUMN on_calls INTEGER NOT NULL DEFAULT 1")
        addColumn(db, "ALTER TABLE blacklist ADD COLUMN call_prompt TEXT")
        addColumn(db, "ALTER TABLE blacklist ADD COLUMN call_prompt_lang TEXT")
        addColumn(db, "ALTER TABLE blacklist ADD COLUMN until_ts INTEGER NOT NULL DEFAULT 0")
        addColumn(db, "ALTER TABLE events ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
    }

    /** Выполнить блок в одной транзакции (массовые вставки на порядок быстрее). */
    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val r = block()
            db.setTransactionSuccessful()
            r
        } finally { db.endTransaction() }
    }

    fun insert(number: String, name: String?, channel: String, direction: String, body: String, ts: Long = System.currentTimeMillis(), auto: Boolean = false) {
        val cv = ContentValues().apply {
            put("number", number); put("name", name); put("channel", channel)
            put("direction", direction); put("body", body); put("ts", ts); put("auto", if (auto) 1 else 0)
        }
        writableDatabase.insert("events", null, cv)
    }

    /** Кол-во авто-ответов (out, auto=1) по каналам с момента from. */
    /** Список авто-ответов (out, auto=1) с момента from — для экрана статистики. */
    fun autoReplies(from: Long): List<HistItem> {
        val res = ArrayList<HistItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM events WHERE direction='out' AND auto=1 AND ts>=? ORDER BY ts DESC",
            arrayOf(from.toString())).use { c -> while (c.moveToNext()) res.add(row(c)) }
        return res
    }

    fun countAuto(from: Long, channels: List<String> = emptyList()): Int {
        val chSql = if (channels.isEmpty()) "" else
            " AND channel IN (${channels.joinToString(",") { "'" + it + "'" }})"
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE direction='out' AND auto=1 AND ts>=?$chSql",
            arrayOf(from.toString())).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Сколько ВХОДЯЩИХ пришло с момента from (для сводки после DND). */
    fun countIncoming(from: Long, channels: List<String> = emptyList(),
                      handledOnly: Boolean = false): Int {
        val chSql = if (channels.isEmpty()) "" else
            " AND channel IN (${channels.joinToString(",") { "'" + it + "'" }})"
        // Сводку смотрят, чтобы понять, сколько дел робот взял на себя. Родня из «Избранных»,
        // банковские рассылки и всё, на что автоответчик не отвечал, в журнале есть (контекст
        // разговора нужен целиком), но в статистику попадать не должны — иначе цифра «12
        // звонков» не значит ничего. «Взято в работу» = по этой ветке за тот же период ушёл
        // авто-ответ: другого следа решение робота в БД не оставляет.
        val handled = if (!handledOnly) "" else
            " AND number IN (SELECT number FROM events WHERE direction='out' AND auto=1 AND ts>=?)"
        val args = if (handledOnly) arrayOf(from.toString(), from.toString())
                   else arrayOf(from.toString())
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE direction='in' AND ts>=?$chSql$handled",
            args).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun clearEvents() {
        writableDatabase.delete("events", null, null)
        PersonThreads.invalidate()   // кэш склейки веток ссылается на ключи, которых больше нет
    }

    fun existsAt(number: String, ts: Long, direction: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM events WHERE number=? AND ts=? AND direction=? LIMIT 1",
            arrayOf(number, ts.toString(), direction)
        ).use { c -> return c.moveToFirst() }
    }

    /**
     * То же сообщение уже записано другим путём?
     *
     * У одного события два источника с разными ключами и разным временем: WhatsApp-сообщение
     * пишет и слушатель уведомлений (ключ — имя из книги или номер как его показал мессенджер,
     * время — момент получения уведомления), и root-мост (ключ — канонический номер, время —
     * отметка сервера из базы мессенджера). Ни по ключу, ни по точному времени такой дубль
     * не поймать, поэтому сверяем по содержимому в окне [windowMs].
     *
     * [keys] — ветки ОДНОГО человека (см. [PersonThreads]), и они обязательны. Сверять один
     * текст по всей базе нельзя: шаблон авто-ответа у всех клиентов одинаковый, и запись
     * второму человеку, которому за те же минуты ушёл тот же текст, просто пропала бы.
     *
     * [excludeKey] — ключ, под которым пишет сам вызывающий; строки под ним в сверку не идут.
     * Это разделяет два несовместимых случая:
     *  - у мессенджеров источники всегда под РАЗНЫМИ ключами, и дубль ищется только между
     *    ними. Без этого два одинаковых коротких сообщения подряд («Ок», «😂» — в базе
     *    Telegram у них разные mid, это правда две реплики) второе бы потеряли;
     *  - у SMS оба источника пишут под ОДНИМ нормализованным номером, и там [excludeKey]
     *    не передаётся — иначе не поймался бы собственный авто-ответ, приехавший обратно
     *    из `content://sms`.
     */
    fun existsNear(keys: List<String>, channel: String, direction: String, body: String,
                   ts: Long, windowMs: Long = 180_000L, excludeKey: String? = null): Boolean {
        val b = body.trim()
        if (b.isEmpty()) return false
        val use = if (excludeKey == null) keys else keys.filter { it != excludeKey }
        if (use.isEmpty()) return false
        val ph = use.joinToString(",") { "?" }
        val args = use.toMutableList()
        args += listOf(channel, direction, b, (ts - windowMs).toString(), (ts + windowMs).toString())
        readableDatabase.rawQuery(
            "SELECT 1 FROM events WHERE number IN ($ph) AND channel=? AND direction=? " +
                "AND TRIM(body)=? AND ts BETWEEN ? AND ? LIMIT 1",
            args.toTypedArray()
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

    /** Все ключи веток (значения events.number) — для склейки истории одного человека. */
    fun distinctKeys(): List<String> {
        val res = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT number FROM events", null)
            .use { c -> while (c.moveToNext()) res.add(c.getString(0)) }
        return res
    }

    /**
     * ХВОСТ переписки — последние [limit] событий по всем [keys] в хронологическом порядке.
     *
     * [thread] сортирует по возрастанию и режет LIMIT'ом, то есть отдаёт САМЫЕ СТАРЫЕ записи.
     * Для контекста LLM это была дыра: у номера с 600+ событиями в промпт уходила переписка
     * полугодовой давности вместо текущей. Здесь берём с конца и разворачиваем.
     *
     * Несколько ключей — потому что каналы одного человека хранятся под разными ключами
     * (номер для звонков/SMS, имя для WhatsApp/Telegram); их собирает
     * [com.davnozdu.autoresponder.store.PersonThreads].
     *
     * [skipCalls] выбрасывает строки журнала звонков («входящий звонок», «исходящий звонок
     * (717с)») — смысла для ответа они не несут, а окно контекста вытесняли целиком.
     */
    fun threadTail(keys: List<String>, limit: Int, skipCalls: Boolean = false): List<HistItem> {
        if (keys.isEmpty()) return emptyList()
        val res = ArrayList<HistItem>()
        val ph = keys.joinToString(",") { "?" }
        val calls = if (skipCalls) " AND channel<>'call'" else ""
        readableDatabase.rawQuery(
            "SELECT * FROM events WHERE number IN ($ph)$calls ORDER BY ts DESC LIMIT $limit",
            keys.toTypedArray()
        ).use { c -> while (c.moveToNext()) res.add(row(c)) }
        return res.asReversed()
    }

    /**
     * Ветки, где ЗА ВАС ответил робот, а живого ответа так и не было.
     *
     * Отбор идёт не по входящим, а по авто-ответам: список нужен затем, что клиенту от
     * нашего имени пообещали ответить в рабочее время, и обещание кто-то должен выполнить.
     * Банковские рассылки, переписка с родными и всё, на что автоответчик не отвечал,
     * сюда не попадают — они не наша задача, а шум, из-за которого список перестают открывать.
     *
     * Авто-ответ ответом НЕ считается: он и есть причина, по которой ветка в списке.
     * Ответом считается наше исходящее, сделанное руками (`auto=0`) — отправленная SMS
     * или исходящий звонок из журнала.
     *
     * [since] отсекает древность: неделю назад «не ответили» — это уже не задача, а история.
     *
     * CAST у параметра обязателен. Android отдаёт параметры запроса ТОЛЬКО строками, а
     * last_in — не колонка, а агрегат, и integer-affinity колонки ts на него не переходит.
     * Сравнение числа со строкой в SQLite всегда ложно (текст «больше» любого числа),
     * поэтому без CAST запрос молча возвращал ноль строк — проверено на живой базе.
     */
    fun needsAnswer(since: Long): List<PendingItem> {
        val res = ArrayList<PendingItem>()
        val sql = """
            SELECT g.number, g.last_in, g.cnt,
                   (SELECT name FROM events WHERE number=g.number AND name IS NOT NULL
                     ORDER BY ts DESC LIMIT 1) AS nm,
                   (SELECT channel FROM events WHERE number=g.number AND ts=g.last_in LIMIT 1) AS ch,
                   (SELECT body FROM events WHERE number=g.number AND ts=g.last_in LIMIT 1) AS bd
              FROM (SELECT number,
                           MAX(CASE WHEN direction='in' THEN ts END) AS last_in,
                           MAX(CASE WHEN direction='out' AND auto=0 THEN ts END) AS last_out,
                           MAX(CASE WHEN direction='out' AND auto=1 THEN ts END) AS last_auto,
                           SUM(CASE WHEN direction='in' THEN 1 ELSE 0 END) AS cnt
                      FROM events GROUP BY number) g
              LEFT JOIN inbox_done d ON d.number = g.number
             WHERE g.last_auto >= CAST(? AS INTEGER)
               AND g.last_in IS NOT NULL
               AND g.last_in > COALESCE(g.last_out, 0)
               AND g.last_in > COALESCE(d.ts, 0)
             ORDER BY g.last_in DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) res.add(PendingItem(
                number = c.getString(0), lastIn = c.getLong(1), incoming = c.getInt(2),
                name = if (c.isNull(3)) null else c.getString(3),
                channel = if (c.isNull(4)) "" else c.getString(4),
                body = if (c.isNull(5)) "" else c.getString(5)))
        }
        return res
    }

    /** «Я этим занялся»: ветка уходит из списка, пока клиент не напишет снова. */
    fun inboxDone(number: String, ts: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply { put("number", number); put("ts", ts) }
        writableDatabase.insertWithOnConflict("inbox_done", null, cv,
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Сколько раз этот адресат звонил начиная с [since] (мс). */
    fun incomingCallCount(keys: List<String>, since: Long): Int {
        if (keys.isEmpty()) return 0
        val ph = keys.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE number IN ($ph) AND channel='call' AND direction='in' AND ts>=?",
            (keys + since.toString()).toTypedArray()
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
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
        readableDatabase.rawQuery("SELECT _id,identity,name,via_llm,prompt,on_sms,on_msgr,on_calls,call_prompt,until_ts,call_prompt_lang FROM blacklist ORDER BY _id DESC", null).use { c ->
            while (c.moveToNext()) res.add(BlackEntry(
                c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2),
                c.getInt(3) == 1, if (c.isNull(4)) null else c.getString(4),
                c.getInt(5) == 1, c.getInt(6) == 1, c.getInt(7) == 1,
                if (c.isNull(8)) null else c.getString(8), c.getLong(9),
                if (c.isNull(10)) "" else c.getString(10)))
        }
        return res
    }
    fun blacklistUpsert(e: BlackEntry) {
        blCache = null
        val cv = ContentValues().apply {
            put("identity", e.identity); put("name", e.name)
            put("via_llm", if (e.viaLlm) 1 else 0); put("prompt", e.prompt)
            put("on_sms", if (e.onSms) 1 else 0); put("on_msgr", if (e.onMsgr) 1 else 0)
            put("on_calls", if (e.onCalls) 1 else 0); put("call_prompt", e.callPrompt)
            put("call_prompt_lang", e.callPromptLang); put("until_ts", e.untilTs)
        }
        if (e.id > 0) writableDatabase.update("blacklist", cv, "_id=?", arrayOf(e.id.toString()))
        else writableDatabase.insert("blacklist", null, cv)
    }
    fun blacklistDelete(id: Long) {
        blCache = null
        writableDatabase.delete("blacklist", "_id=?", arrayOf(id.toString()))
    }

    /** Удалить записи с истёкшим сроком блокировки. Возвращает, сколько удалено. */
    fun blacklistPurgeExpired(now: Long = System.currentTimeMillis()): Int {
        blCache = null
        return writableDatabase.delete("blacklist", "until_ts>0 AND until_ts<=?", arrayOf(now.toString()))
    }

    // Чёрный список читается на КАЖДЫЙ входящий звонок — в т.ч. из onScreenCall, у которого
    // жёсткий таймаут. Держим его в памяти и сбрасываем при изменении.
    @Volatile private var blCache: List<BlackEntry>? = null
    private fun blacklistCached(): List<BlackEntry> =
        blCache ?: blacklistAll().also { blCache = it }

    /**
     * Совпадение по номеру (хвост цифр) или имени.
     * Имя тоже может оказаться номером — Telegram показывает телефон, если у контакта не
     * задано имя. В этом случае сравниваем по цифрам, а не посимвольно.
     */
    /**
     * Запись ЧС для входящего. Сопоставление такое же, как в «Избранных»: номер — по последним
     * 9 цифрам (формат не важен), имя — точно и без учёта регистра, плюс маски `*` и `?`
     * (см. [com.davnozdu.autoresponder.rules.NameMatch]) — иначе одно и то же правило пришлось бы
     * заводить отдельно для «Избранных» и для ЧС.
     */
    fun blacklistMatch(number: String?, name: String?): BlackEntry? {
        val numTail = number?.filter { it.isDigit() }?.takeLast(9)
        val nm = name?.trim()?.lowercase()
        val now = System.currentTimeMillis()
        for (e in blacklistCached()) {
            if (e.expired(now)) continue   // срок блокировки вышел — запись больше не действует
            val id = e.identity.trim()
            if (com.davnozdu.autoresponder.rules.NameMatch.hasWildcard(id)) {
                if (com.davnozdu.autoresponder.rules.NameMatch.matches(name, id)) return e
                if (com.davnozdu.autoresponder.rules.NameMatch.matches(number, id)) return e
                continue   // маска сравнивается только как маска
            }
            val eDigits = id.filter { it.isDigit() }
            if (numTail != null && eDigits.length >= 8 && eDigits.takeLast(9) == numTail) return e
            if (nm != null && id.lowercase() == nm) return e
            if (nm != null && e.name?.trim()?.lowercase() == nm) return e
            if (nm != null && com.davnozdu.autoresponder.rules.PhoneMask.sameNumber(nm, id)) return e
        }
        return null
    }

    // --- Ожидающие уведомления о чёрном списке ---
    fun blPendingAdd(number: String?, name: String?, channel: String) {
        val cv = ContentValues().apply { put("number", number); put("name", name); put("channel", channel); put("ts", System.currentTimeMillis()) }
        writableDatabase.insert("bl_pending", null, cv)
    }
    fun blPendingNames(): List<String> {
        val res = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT COALESCE(name, number) FROM bl_pending ORDER BY ts DESC", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { res.add(it) }
        }
        return res
    }
    fun blPendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM bl_pending", null).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
    fun blPendingClear() { writableDatabase.delete("bl_pending", null, null) }

    // --- Придержанные до утра авто-SMS (тихий час) ---
    fun smsHoldAdd(number: String) {
        val cv = ContentValues().apply { put("number", number); put("ts", System.currentTimeMillis()) }
        writableDatabase.insert("sms_hold", null, cv)
    }
    /** Номера без повторов: три ночных звонка с одного номера — одна утренняя SMS. */
    fun smsHoldNumbers(): List<String> {
        val res = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT number FROM sms_hold ORDER BY ts", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { res.add(it) }
        }
        return res
    }
    fun smsHoldRemove(number: String) {
        writableDatabase.delete("sms_hold", "number = ?", arrayOf(number))
    }
    fun smsHoldCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sms_hold", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
    fun smsHoldClear() { writableDatabase.delete("sms_hold", null, null) }

    fun humanReplyAfter(identity: String, since: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM events WHERE number=? AND direction='out' AND auto=0 AND channel!='call' AND ts>? LIMIT 1",
        arrayOf(identity,since.toString())).use { it.moveToFirst() }

    /** Принудительный WAL-checkpoint перед копированием файла БД (для бэкапа). */
    fun checkpoint() {
        try { writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } } catch (_: Exception) {}
    }

    companion object {
        /** Версия схемы — сверяется в [com.davnozdu.autoresponder.store.Backup.validate], чтобы
         *  не разойтись с магическим числом там при следующем изменении схемы. */
        const val DB_VERSION = 11

        /**
         * Окно сверки дублей для мессенджеров. Отметка времени у мессенджера серверная, а
         * уведомление приходит с задержкой доставки: на устройстве наблюдались расхождения
         * до семи минут (293, 299 и 413 секунд), и трёх минут не хватало — дубли проходили.
         */
        const val MSGR_WINDOW_MS = 600_000L

        @Volatile private var inst: HistoryDb? = null
        fun get(context: Context): HistoryDb =
            inst ?: synchronized(this) { inst ?: HistoryDb(context.applicationContext).also { inst = it } }

        /** Закрыть и обнулить синглтон — следующий get() пересоздаст (нужно при восстановлении из бэкапа). */
        @Synchronized fun reset() {
            try { inst?.close() } catch (_: Exception) {}
            inst = null
        }
    }
}
