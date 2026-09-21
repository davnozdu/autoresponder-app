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

    // Номер -> отпечаток состояния его записей в CRM (пустая строка, если CRM старая).
    @Volatile private var cache: Map<String, String>? = null

    /**
     * Когда реестр в последний раз подтверждён — в ОЗУ, а не в настройках. Раньше это
     * писалось в SharedPreferences на каждом успешном опросе, то есть раз в 15 минут шла
     * запись на флеш и при ответе 304, когда не менялось вообще ничего. Флеш телефона
     * ресурс расходуемый, ради счётчика его тратить незачем.
     *
     * Побочный эффект ровно тот, что нужен: после перезапуска процесса значение 0, кеш
     * ответов тоже пуст (он в памяти), и приложение делает один поход за свежим.
     */
    @Volatile private var confirmedAt = 0L

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /** Формат файла: `номер` или `номер<TAB>отпечаток` — старые файлы читаются как есть. */
    private fun load(context: Context): Map<String, String> {
        cache?.let { return it }
        val map = try {
            val f = file(context)
            if (!f.exists()) emptyMap()
            else f.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty()) null
                else {
                    val i = t.indexOf('\t')
                    if (i < 0) t to "" else t.substring(0, i) to t.substring(i + 1)
                }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
        cache = map
        return map
    }

    private fun save(context: Context, phones: List<String>, stamps: Map<String, String>) {
        val map = phones.associateWith { stamps[it].orEmpty() }
        try {
            file(context).writeText(map.entries.joinToString("\n") { (k, v) ->
                if (v.isEmpty()) k else "$k\t$v" })
            cache = map
        } catch (e: Exception) {
            EventLog(context).add("CRM реестр: не удалось сохранить (${e.message})")
        }
    }

    /**
     * Отпечаток состояния для номера: пока он тот же, ответ CRM про этого клиента не
     * изменился. Пусто — CRM старой версии или номера нет в реестре; тогда кеш ответа
     * живёт по времени, как раньше.
     */
    fun stampFor(context: Context, phone: String): String = load(context)[key(phone)].orEmpty()

    fun size(context: Context): Int = load(context).size

    fun isFresh(context: Context): Boolean = CrmSyncPolicy.isFresh(
        System.currentTimeMillis(), confirmedAt, Settings(context).crmRosterAt, FRESH_MS)

    /**
     * Реестр подтверждён. В память — всегда, на флеш — не чаще раза в час: постоянная
     * запись изнашивает память телефона, а полностью без неё после перезапуска без сети
     * реестру нечем было бы верить.
     */
    private fun confirm(context: Context) {
        val now = System.currentTimeMillis()
        confirmedAt = now
        val s = Settings(context)
        if (CrmSyncPolicy.shouldPersist(now, s.crmRosterAt, FRESH_MS)) s.crmRosterAt = now
    }

    /** Есть ли номер в реестре — без сети и без разбора свежести. */
    fun contains(context: Context, phone: String): Boolean {
        val k = key(phone)
        return k.length >= 6 && k in load(context).keys
    }

    /** Номера с активными записями — для прогрева кеша при запуске. */
    fun numbers(context: Context): List<String> = load(context).keys.toList()

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
        if (phones.any { key(it).length >= 6 && key(it) in roster.keys }) return true
        return !isFresh(context)
    }

    /** Пора ли синхронизироваться. */
    fun dueForSync(context: Context): Boolean =
        System.currentTimeMillis() - confirmedAt >= SYNC_EVERY_MS

    /** Сколько прошло с последнего подтверждения реестра — для CrmSyncPolicy. */
    fun sinceConfirmed(): Long = System.currentTimeMillis() - confirmedAt
    const val SYNC_INTERVAL_MS = SYNC_EVERY_MS

    /**
     * Синхронизация. Вызывается фоном перед обработкой события — реестр маленький,
     * а ETag делает неизменившийся ответ бесплатным.
     */
    /**
     * @param force послать пустой ETag — сервер ответит телом, и файл перепишется.
     * @param ignoreThrottle обойти 15-минутный порог, но сохранить ETag: неизменившийся
     *        реестр вернётся как 304, и на флеш ничего не ляжет.
     */
    fun sync(context: Context, force: Boolean = false, ignoreThrottle: Boolean = force): Boolean {
        val s = Settings(context)
        if (!s.crmReady) return false
        if (!ignoreThrottle && !dueForSync(context)) return false

        return when (val res = CrmApi.roster(context, if (force) "" else s.crmRosterEtag)) {
            is RosterResult.Ok -> {
                // Изменившийся отпечаток = ответ про этого клиента устарел; выбрасываем
                // ровно его, а не весь кеш: у остальных ответ по-прежнему верен.
                val old = load(context)
                res.phones.forEach { p ->
                    if (old[p] != res.stamps[p].orEmpty()) CrmFlow.invalidate(p)
                }
                old.keys.filterNot { it in res.stamps.keys || it in res.phones }
                    .forEach { CrmFlow.invalidate(it) }   // заказ закрыт — номер выпал из реестра
                save(context, res.phones, res.stamps)
                s.crmRosterEtag = res.etag
                confirm(context)
                EventLog(context).add("CRM реестр: ${res.phones.size} номеров с активными записями")
                true
            }
            RosterResult.NotModified -> {
                confirm(context)
                true
            }
            is RosterResult.Error -> {
                EventLog(context).add("CRM реестр: не обновился (${res.why})")
                false
            }
        }
    }

    /**
     * Проверка связи для кнопки в настройках — с человеческим объяснением, что не так.
     *
     * Раньше кнопка отвечала «не вышло, проверь адрес, токен и приём» — то есть перечисляла
     * всё сразу. Между тем причина в ответе есть почти всегда, надо только её показать.
     *
     * @return null, если всё в порядке, иначе — что именно случилось.
     */
    fun check(context: Context): String? {
        val s = Settings(context)
        if (!s.crmEnabled) return "Функция выключена — включите переключатель выше."
        if (s.crmBaseUrl.isBlank()) return "Не указан адрес CRM."
        if (s.crmToken.isBlank()) return "Не вписан токен."
        if (!com.davnozdu.autoresponder.respond.NetworkUtil.isOnline(context))
            return "Нет интернета."

        return when (val res = CrmApi.roster(context, "")) {
            is RosterResult.Ok -> {
                save(context, res.phones, res.stamps)
                s.crmRosterEtag = res.etag
                confirm(context)
                CrmFlow.invalidateAll()
                null
            }
            // Пустого ETag мы не посылали, так что 304 сюда прийти не может; на всякий
            // случай считаем связь исправной.
            RosterResult.NotModified -> { confirm(context); null }
            is RosterResult.Error -> explain(res.why)
        }
    }

    /** Ответ сервера → что с этим делать. */
    private fun explain(why: String): String = when {
        // Защита хостинга отдаёт свою страницу вместо ответа CRM.
        why.contains("antirobot", true) || why.contains("hostia", true) ||
        why.contains("HTTP 416") ->
            "Запрос перехватила защита хостинга («я не робот»). Попросите хостинг исключить " +
            "из проверки адрес index.php?r=bot. или User-Agent приложения."

        // Оба случая CRM отвечает одинаково намеренно — но подсказать, где смотреть, можно.
        why.contains("not_found") || why.contains("HTTP 404") ->
            "CRM ответила «не найдено». Это либо не тот токен, либо в CRM не включён приём " +
            "(Настройки → Автоответчик → «Принимать запросы»). Что именно — видно там же в журнале."

        why.contains("HTTP 429") ->
            "CRM ограничила частоту запросов. Подождите минуту."

        why.contains("UnknownHost", true) ->
            "Адрес не разрешается — проверьте написание адреса CRM."

        why.contains("Timeout", true) || why.contains("timed out", true) ->
            "CRM не ответила вовремя. Возможно, приложению закрыт доступ в сеть в файрволе."

        why.contains("SSL", true) || why.contains("Trust", true) || why.contains("Certificate", true) ->
            "Не проходит проверка сертификата — адрес должен начинаться с https://."

        why.contains("не JSON") || why.contains("<!") || why.contains("<html", true) ->
            "Вместо ответа CRM пришла веб-страница: $why"

        else -> "Не вышло: $why"
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) {}
        cache = null
        confirmedAt = 0L
        Settings(context).let { it.crmRosterAt = 0L; it.crmRosterEtag = "" }
    }
}
