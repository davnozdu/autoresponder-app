package com.davnozdu.autoresponder.crm

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import java.io.File

/**
 * Реестр номеров, у которых в CRM есть что-то в работе.
 *
 * Существует ради одного: на незнакомый номер в CRM не ходить вовсе. Клиентов у
 * мастерской сотни, а пишут в основном не они.
 *
 * Лежит НЕ в history.db: та ежедневно копируется в /sdcard/AutoResponder/backups,
 * то есть на общее хранилище, и списку телефонов клиентов там не место. Здесь —
 * обычный файл в приватном каталоге приложения.
 */
object CrmRoster {

    private const val FILE = "crm_roster.txt"
    private const val FRESH_MS = 60 * 60_000L        // час
    private const val SYNC_EVERY_MS = 15 * 60_000L   // как часто пытаемся обновить

    @Volatile private var cache: Set<String>? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun load(context: Context): Set<String> {
        cache?.let { return it }
        val set = try {
            val f = file(context)
            if (!f.exists()) emptySet()
            else f.readLines().mapNotNull { it.trim().ifBlank { null } }.toSet()
        } catch (_: Exception) { emptySet() }
        cache = set
        return set
    }

    private fun save(context: Context, phones: List<String>) {
        try {
            file(context).writeText(phones.joinToString("\n"))
            cache = phones.toSet()
        } catch (e: Exception) {
            EventLog(context).add("CRM реестр: не удалось сохранить (${e.message})")
        }
    }

    fun size(context: Context): Int = load(context).size

    fun isFresh(context: Context): Boolean =
        System.currentTimeMillis() - Settings(context).crmRosterAt < FRESH_MS

    /** Последние 9 цифр — та же договорённость, что у номеров везде в приложении. */
    private fun key(phone: String?): String {
        val d = phone?.filter { it.isDigit() } ?: return ""
        return if (d.length > 9) d.takeLast(9) else d
    }

    /**
     * Стоит ли идти в CRM за этим номером.
     *
     * Асимметрия намеренная: лишний запрос безвреден, а неузнанный клиент — нет.
     * Поэтому «нет в реестре» останавливает только пока реестр свежий; если синк
     * отвалился или клиента завели полчаса назад, сходим и спросим.
     */
    fun shouldAsk(context: Context, phones: List<String>): Boolean {
        if (phones.isEmpty()) return false
        val roster = load(context)
        if (roster.isEmpty()) return !isFresh(context)
        if (phones.any { key(it).length >= 6 && key(it) in roster }) return true
        return !isFresh(context)
    }

    /** Пора ли синхронизироваться. */
    fun dueForSync(context: Context): Boolean =
        System.currentTimeMillis() - Settings(context).crmRosterAt >= SYNC_EVERY_MS

    /**
     * Синхронизация. Вызывается фоном перед обработкой события — реестр маленький,
     * а ETag делает неизменившийся ответ бесплатным.
     */
    fun sync(context: Context, force: Boolean = false): Boolean {
        val s = Settings(context)
        if (!s.crmReady) return false
        if (!force && !dueForSync(context)) return false

        return when (val res = CrmApi.roster(context, if (force) "" else s.crmRosterEtag)) {
            is RosterResult.Ok -> {
                save(context, res.phones)
                s.crmRosterEtag = res.etag
                s.crmRosterAt = System.currentTimeMillis()
                EventLog(context).add("CRM реестр: ${res.phones.size} номеров с активными записями")
                true
            }
            RosterResult.NotModified -> {
                s.crmRosterAt = System.currentTimeMillis()
                true
            }
            is RosterResult.Error -> {
                EventLog(context).add("CRM реестр: не обновился (${res.why})")
                false
            }
        }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) {}
        cache = null
        Settings(context).let { it.crmRosterAt = 0L; it.crmRosterEtag = "" }
    }
}
