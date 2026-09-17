package com.davnozdu.autoresponder.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.PhoneMask
import java.io.File

/**
 * Импорт переписки Telegram из копии `cache4.db`, которую кладёт root-мост модуля.
 *
 * Причина та же, что у [WaImporter]: через уведомления не видно ни того, что владелец
 * написал сам, ни входящих при выключенном роботе. Плюс уведомления Telegram отдают
 * имя из книги контактов, а не номер, поэтому ветка мессенджера не склеивалась с SMS.
 * Здесь номер берётся из `users.data`, и ветки сходятся сами.
 *
 * Текст лежит не колонкой, а внутри TL-блоба — разбор в [TlBlob].
 *
 * Берём ТОЛЬКО переписку с людьми. В Telegram это говорит сам идентификатор диалога:
 * `uid > 0` — личный чат, отрицательный — группа или канал, и такие сюда не попадают вовсе.
 * Сверх того отсеиваются боты (Telegram требует у их username суффикс «bot») и служебные
 * псевдо-аккаунты — см. [SERVICE_UIDS]. Лента новостей или ошибок собственного сервиса в
 * контексте разговора с клиентом не нужна, а окно контекста она вытесняет целиком: у самого
 * болтливого бота в базе устройства сообщений было больше, чем у клиента.
 */
object TgImporter {

    private const val FIRST_RUN_DAYS = 120L

    /**
     * Аккаунты, которые собеседниками не являются, а ветку в журнале заводят такую же, как
     * живой клиент: 777000 — системные сообщения самого Telegram (коды входа, предупреждения
     * о новых сеансах), 1271266957 — «Replies» (ответы на посты каналов), 93372553 —
     * BotFather.
     *
     * BotFather перечислен поимённо: Telegram требует, чтобы username бота оканчивался на
     * «bot», а этот аккаунт — известное исключение из их же правила, и общая проверка по
     * суффиксу его не ловит.
     */
    private val SERVICE_UIDS = setOf(777000L, 1271266957L, 93372553L)

    /** @return число добавленных записей, -1 если базы нет или её не открыть. */
    fun import(context: Context, dbFile: File, since: Long): Int {
        if (!dbFile.exists()) return -1
        val prefix = com.davnozdu.autoresponder.data.Settings(context).aiPrefix.trim()
        val log = EventLog(context)
        val db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            log.add("TG импорт: база не открылась (${e.message})"); return -1
        }
        // Секунды: у Telegram `date` в секундах, у нашей истории — в миллисекундах.
        val fromSec = ((if (since > 0) since
            else System.currentTimeMillis() - FIRST_RUN_DAYS * 86_400_000L) / 1000L)
        var added = 0
        val threads = HashMap<String, List<String>>()
        try {
            val people = loadPeople(context, db)
            val hist = HistoryDb.get(context)
            hist.inTransaction {
                db.rawQuery(
                    "SELECT uid, date, out, data FROM messages_v2 " +
                        "WHERE uid > 0 AND date > ? ORDER BY date LIMIT 4000",
                    arrayOf(fromSec.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val uid = c.getLong(0)
                        val person = people[uid] ?: continue   // бот или незнакомец без имени
                        val dateSec = c.getInt(1)
                        val dir = if (c.getInt(2) == 1) "out" else "in"
                        val blob = c.getBlob(3) ?: continue
                        if (TlBlob.isService(blob)) continue   // звонки и «вошёл в чат» — не реплики
                        val body = TlBlob.messageText(blob, dateSec)?.trim().orEmpty()
                        if (body.isEmpty()) continue           // медиа без подписи
                        val ts = dateSec * 1000L
                        if (dir == "out") com.davnozdu.autoresponder.respond.Outgoing.observed(context, person.key, "telegram", body, ts)
                        if (hist.existsAt(person.key, ts, dir)) continue
                        val keys = threads.getOrPut(person.key) { PersonThreads.keysFor(context, person.key) }
                        // Только чужие ключи — см. WaImporter: свои повторы это настоящие
                        // реплики (в базе Telegram у них разные mid).
                        if (hist.existsNear(keys, "telegram", dir, body, ts,
                                HistoryDb.MSGR_WINDOW_MS, excludeKey = person.key)) continue
                        // Робот свои ответы шлёт с префиксом ИИ. Без этой пометки экран
                        // «Требуют ответа» счёл бы авто-ответ живым ответом и убрал ветку
                        // из списка — обещание «ответим в рабочее время» осталось бы ничьим.
                        val auto = dir == "out" && prefix.isNotEmpty() &&
                            body.startsWith(prefix, ignoreCase = true)
                        hist.insert(person.key, person.name, "telegram", dir, body, ts, auto = auto)
                        added++
                    }
                }
                added
            }
        } catch (e: Exception) {
            log.add("TG импорт: ошибка (${e.message})")
        } finally {
            try { db.close() } catch (_: Exception) {}
        }
        if (added > 0) PersonThreads.invalidate()
        return added
    }

    /** Ключ ветки и имя для одного собеседника. */
    private data class Person(val key: String, val name: String?)

    /**
     * Собеседники Telegram: uid → (ключ ветки, имя).
     *
     * `users.name` — это «имя фамилия;;;username». Ключом берём номер, если он известен:
     * тогда ветка Telegram и ветка SMS одного человека сходятся по номеру, а не по имени,
     * которое в Telegram и в книге контактов может отличаться.
     */
    private fun loadPeople(context: Context, db: SQLiteDatabase): Map<Long, Person> {
        val out = HashMap<Long, Person>()
        db.rawQuery("SELECT uid, name, data FROM users", null).use { c ->
            while (c.moveToNext()) {
                val uid = c.getLong(0)
                if (uid <= 0 || uid in SERVICE_UIDS) continue
                val raw = c.getString(1).orEmpty()
                val parts = raw.split(';')
                val display = parts.getOrNull(0)?.trim().orEmpty()
                val usernames = parts.drop(1).joinToString(" ").lowercase()
                // Собственный сервис-бот шлёт сотни служебных сообщений — в контексте
                // разговора с клиентом это шум, вытесняющий саму переписку.
                if (usernames.split(' ', ';').any { it.endsWith("bot") }) continue
                if (display.endsWith("bot", ignoreCase = true)) continue
                val num = c.getBlob(2)
                    ?.let { TlBlob.userPhone(it) }
                    ?.let { PhoneMask.canonical(it) }
                    ?.takeIf { PhoneMask.looksLikeNumber(it) }
                if (num == null && display.isEmpty()) continue
                val key = num ?: display
                val name = (if (num != null) ContactUtil.nameFor(context, num) else null)
                    ?: display.ifEmpty { null }
                out[uid] = Person(key, name)
            }
        }
        return out
    }
}
