package com.davnozdu.autoresponder.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.davnozdu.autoresponder.data.EventLog
import java.io.File

/**
 * Бот ли это в Telegram — по копии базы мессенджера, которую кладёт root-мост модуля.
 *
 * Зачем база. В уведомлении Telegram стоит ОТОБРАЖАЕМОЕ имя, а «ботность» видна только по
 * username: у `@bigtweak_post_bot` уведомление приходит от «BigTweak autopost», у
 * `@hermes_nascz_bot` — от «NAS-Hermes». Проверка по отображаемому имени (а раньше была
 * только она) не ловила ни того, ни другого, и робот отвечал ботам: 13.09 «NAS-Hermes»
 * получил три автоответа подряд.
 *
 * В базе Telegram столбец `users.name` хранит строку вида `отображаемое имя;;;username`,
 * причём в нижнем регистре. Этого достаточно: правило Telegram требует, чтобы username бота
 * оканчивался на «bot». Ровно по такому же правилу боты уже отсеиваются из журнала
 * (см. [TgImporter]) — здесь та же проверка, только на пути ответа.
 *
 * Модуля может не быть, копии может не быть — тогда остаётся проверка по отображаемому имени,
 * то есть поведение не хуже прежнего.
 */
object TgBots {

    /**
     * Аккаунты-исключения из правила Telegram: username у них на «bot» не оканчивается.
     * 93372553 — BotFather, 777000 — системные сообщения Telegram, 1271266957 — «Replies».
     * Тот же список, что и в [TgImporter].
     */
    private val SERVICE_UIDS = setOf(777000L, 1271266957L, 93372553L)

    private fun dbFile(context: Context) = File(context.filesDir, "bridge/telegram/cache4.db")

    /** Отображаемые имена всех ботов, какие нашлись в базе. Перечитываем, когда копия сменилась. */
    @Volatile private var stamp = -1L
    @Volatile private var names: Set<String> = emptySet()

    /**
     * Строка `users.name` принадлежит боту?
     *
     * Вынесено отдельно и без Android: правило чисто текстовое и проверяется тестами.
     * Смотрим и username, и отображаемое имя — у старых ботов username бывает без суффикса
     * («votebot;;;vote»), зато имя о нём говорит.
     */
    fun isBotRow(raw: String): Boolean {
        val parts = raw.split(';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.any { it.endsWith("bot") }
    }

    /** Отображаемое имя из строки `users.name`. */
    fun displayOf(raw: String): String = raw.substringBefore(';').trim().lowercase()

    /**
     * Отправитель уведомления Telegram — бот?
     *
     * @param sender строка отправителя ровно в том виде, в каком её показало уведомление.
     */
    fun isBot(context: Context, sender: String): Boolean {
        val who = sender.trim().lowercase()
        if (who.isEmpty()) return false
        // Имя само о себе говорит — база не нужна, работает и без модуля.
        if (who.endsWith("bot")) return true
        return who in load(context.applicationContext)
    }

    private fun load(app: Context): Set<String> {
        val f = dbFile(app)
        val mtime = if (f.exists()) f.lastModified() else 0L
        if (mtime == stamp) return names
        // Отметку ставим до разбора: не открылась база или сменилась схема — не долбимся
        // в неё на каждое сообщение, ждём следующей копии.
        stamp = mtime
        if (mtime == 0L) { names = emptySet(); return names }
        names = read(app, f)
        return names
    }

    private fun read(app: Context, f: File): Set<String> {
        val db = try {
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            EventLog(app).add("Telegram: база для проверки ботов не открылась (${e.message})")
            return emptySet()
        }
        return try {
            val out = HashSet<String>()
            // 13 тысяч строк в таблице, «bot» встречается в сотне — отбираем их запросом,
            // а не перебором. Имя, оканчивающееся на «bot», под LIKE попадает заведомо.
            db.rawQuery("SELECT uid, name FROM users WHERE name LIKE '%bot%'", null).use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(1).orEmpty()
                    if (isBotRow(raw)) displayOf(raw).takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
            db.rawQuery(
                "SELECT name FROM users WHERE uid IN (${SERVICE_UIDS.joinToString(",")})", null
            ).use { c ->
                while (c.moveToNext()) {
                    displayOf(c.getString(0).orEmpty()).takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
            out
        } catch (e: Exception) {
            // Схему Telegram меняет без предупреждения — это не повод падать.
            EventLog(app).add("Telegram: список ботов не прочитался (${e.message})")
            emptySet()
        } finally {
            try { db.close() } catch (_: Exception) {}
        }
    }
}
