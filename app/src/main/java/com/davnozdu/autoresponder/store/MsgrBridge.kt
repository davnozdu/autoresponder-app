package com.davnozdu.autoresponder.store

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import java.io.File

/**
 * Мост к базам мессенджеров: просит модуль обновить копии и переносит из них переписку
 * в журнал.
 *
 * Зачем вообще: из уведомлений видно только то, что показала система — входящее, пока
 * робот включён, не старше пяти минут, с кнопкой «Ответить». Сообщения, которые владелец
 * написал клиенту руками, уведомлений не порождают вовсе, и в контекст LLM уходил
 * односторонний монолог клиента: робот отвечал так, будто разговора не было.
 *
 * Базы мессенджеров лежат под чужим uid с правами 0600 — приложению их не открыть. Root
 * есть у модуля, поэтому копирование делает он (`bridge.sh`), а разбор схемы остаётся
 * здесь: в shell ни схему WhatsApp, ни TL-блобы Telegram не разобрать, а в Kotlin это
 * покрывается тестами.
 *
 * Протокол — три файла в приватной папке приложения (на `/sdcard` переписка клиентов
 * была бы видна любому приложению с доступом к хранилищу):
 *   `bridge/request`   пишем «<epoch>» — обычный прогон, «<epoch> <метки>» — только эти базы
 *   `bridge/<канал>/`  копии баз
 *   `bridge/response`  «<epoch> ok|partial|none <каналы>»
 *
 * Сами копии модуль держит в ОЗУ (tmpfs поверх этой папки) и переписывает только те файлы,
 * что изменились у источника. Копия одноразовая: после перезагрузки её нет, модуль снимает
 * её заново при старте. Отсюда и правило здесь — просить обновление там, где свежесть
 * действительно нужна, а не по таймеру каждые несколько минут.
 *
 * Модуля может не быть (приложение поставили без него) или он может быть старым — тогда
 * `request` никто не читает, ответ не обновляется, и мы просто работаем по тому, что есть.
 * Автоответчик не должен переставать отвечать из-за отсутствия моста.
 */
object MsgrBridge {

    private const val DIR = "bridge"
    /**
     * Время, РАНЬШЕ которого фоновый проход не делаем.
     *
     * Раньше хранилось время последней попытки, и отсчёт шёл от неё независимо от исхода.
     * После перезагрузки это стоило часа простоя: модуль поднимает копии при старте, а
     * приложение подключается раньше (15.09 — на 44 секунды), не застаёт ничего и всё
     * равно откладывает следующий проход на час. Теперь час отсчитывается только от
     * УДАЧНОГО прохода, а после неудачного пробуем снова через [RETRY_GAP_MS].
     */
    private const val K_NEXT_TRY = "bridge_next_sync"
    private const val K_WA = "bridge_wm_wa"
    private const val K_WA2 = "bridge_wm_wa2"
    private const val K_TG = "bridge_wm_tg"

    /** Сколько ждём модуль после просьбы обновить копии. */
    private const val WAIT_MS = 12_000L
    private const val POLL_MS = 300L

    /**
     * Сколько ждём на пути ответа клиенту.
     *
     * Там ожидание идёт поверх запроса к LLM, поэтому бюджет отдельный и короткий: копии
     * теперь лежат в ОЗУ и обновляются только по изменившимся файлам, обычный прогон
     * укладывается в доли секунды. Не успел — отвечаем по тому, что есть, как и раньше.
     */
    private const val REPLY_WAIT_MS = 4_000L

    /**
     * Фоновый проход — не чаще раза в час.
     *
     * Раньше было две минуты, и будильник heartbeat дёргал мост каждые десять минут:
     * 144 полных копирования в сутки, около 26 ГБ записи на флеш ради нескольких новых
     * сообщений. Свежесть от этого не зависит — там, где она нужна (перед ответом клиенту,
     * при включении «Не беспокоить», на экране состояния), проход идёт принудительно и
     * с ожиданием. Час — это страховка на случай, если ни одно из событий не случилось.
     */
    private const val MIN_GAP_MS = 60 * 60_000L

    /**
     * Через сколько пробовать снова, если модуль не ответил.
     *
     * Совпадает с периодом будильника heartbeat — значит следующая же его отработка и будет
     * повторной попыткой, отдельного будильника не нужно.
     */
    private const val RETRY_GAP_MS = 10 * 60_000L

    /**
     * Насколько отступаем назад от водяного знака при следующем чтении.
     *
     * Знак — время снятия копии, а мессенджер записывает сообщение позже, чем стоит его
     * отметка времени (у сервера она своя). Сообщение, попавшее в базу сразу после копии,
     * но с отметкой чуть раньше, иначе не вернулось бы никогда. Перекрытие безвредно:
     * повторы отсекает дедуп в [HistoryDb].
     */
    private const val OVERLAP_MS = 900_000L

    /** Бюджет ожидания для пути ответа клиенту — см. [REPLY_WAIT_MS]. */
    fun replyWaitMs(): Long = REPLY_WAIT_MS

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Отдельный файл настроек: водяные знаки не должны попадать в экспорт/восстановление
     *  настроек — иначе на чистой установке первый импорт решил бы, что всё уже втянуто. */
    private fun prefs(context: Context) =
        context.getSharedPreferences("autoresp_bridge", Context.MODE_PRIVATE)

    private fun since(p: android.content.SharedPreferences, key: String): Long {
        val wm = p.getLong(key, 0L)
        return if (wm <= 0L) 0L else (wm - OVERLAP_MS).coerceAtLeast(0L)
    }

    /** Есть ли мост вообще: модуль создаёт папку при старте. */
    fun available(context: Context): Boolean = dir(context).isDirectory

    /**
     * Когда модуль последний раз обновил копии — для экрана состояния.
     *
     * Берём время самого файла ответа, а не нашу отметку о попытке: попытка делается и
     * когда модуля нет, и тогда строка состояния показывала бы «всё свежо» при мёртвом
     * мосте — то есть ровно в том случае, ради которого её и смотрят.
     */
    fun lastSync(context: Context): Long = File(dir(context), "response").lastModifiedSafe()

    /**
     * Обновить копии баз и НЕ импортировать.
     *
     * Нужен там, где базу спрашивают напрямую: номер отправителя RCS ищется в копии
     * `bugle_db` по тексту сообщения (см. [RcsFinder]), и копия должна быть свежее этого
     * сообщения. Импорт в этот момент лишний — клиент ждёт ответа.
     */
    fun refresh(context: Context, vararg tags: String) {
        val app = context.applicationContext
        val d = dir(app)
        if (!d.isDirectory) return
        if (!lock.tryLock()) return
        try {
            val resp = File(d, "response")
            val before = resp.lastModifiedSafe()
            try { File(d, "request").writeText(ask(tags)) }
            catch (e: Exception) {
                EventLog(app).add("Мост: не удалось попросить обновление (${e.message})"); return
            }
            waitForResponse(resp, before, WAIT_MS)
        } finally { lock.unlock() }
    }

    /**
     * Текст просьбы: `<epoch>` — обычный прогон, `<epoch> <метки>` — только названные базы.
     *
     * Адресность нужна тяжёлым базам, которые в обычный прогон не входят: копия Google
     * Messages весит 17 МБ, а нужна редко — когда RCS-отправитель пришёл без номера.
     */
    private fun ask(tags: Array<out String>): String {
        val now = System.currentTimeMillis()
        return if (tags.isEmpty()) now.toString() else "$now ${tags.joinToString(" ")}"
    }

    /**
     * Синхронизацию ведём по одному. Замок, а не `@Synchronized`, потому что вызывающие
     * разные: фоновый будильник может ждать модуль до [WAIT_MS], а путь ответа клиенту
     * ждать не должен вовсе — он просто пропускает проход, если синхронизация уже идёт.
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * Обновить копии и втянуть новое в журнал.
     *
     * @param force игнорировать [MIN_GAP_MS] — для ручной кнопки и для включения «Не беспокоить»,
     *              когда нужно разом подобрать всё, что владелец написал за день.
     * @param wait ждать, пока модуль обновит копии.
     * @param waitMs бюджет ожидания; на пути ответа клиенту — короткий [REPLY_WAIT_MS],
     *               потому что там ожидание ложится поверх запроса к LLM.
     * @return число добавленных записей, -1 если моста нет.
     */
    fun sync(context: Context, force: Boolean = false, wait: Boolean = true,
             waitMs: Long = WAIT_MS): Int {
        if (wait) lock.lock() else if (!lock.tryLock()) return 0
        try {
            return syncLocked(context, force, wait, waitMs)
        } finally { lock.unlock() }
    }

    private fun syncLocked(context: Context, force: Boolean, wait: Boolean, waitMs: Long): Int {
        val app = context.applicationContext
        val log = EventLog(app)
        val d = dir(app)
        if (!d.isDirectory) return -1

        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (!force && now < p.getLong(K_NEXT_TRY, 0L)) return 0

        val resp = File(d, "response")
        val before = resp.lastModifiedSafe()
        // Пишем в request — модуль слушает его через inotifyd и копирует базы. Именно
        // ЗАПИСЬ, а не setLastModified: тот на части файловых систем молча возвращает false,
        // и просьба не уходила бы вовсе.
        val req = File(d, "request")
        val asked = try {
            req.writeText(now.toString())
            true
        } catch (e: Exception) {
            log.add("Мост: не удалось попросить обновление (${e.message})"); false
        }
        if (asked && wait) waitForResponse(resp, before, waitMs)

        var total = 0
        importOne(app, File(d, "whatsapp/msgstore.db"), "whatsapp", K_WA, p)?.let { total += it }
        importOne(app, File(d, "whatsapp2/msgstore.db"), "whatsapp", K_WA2, p)?.let { total += it }
        importTelegram(app, File(d, "telegram/cache4.db"), p)?.let { total += it }

        // Модуль ответил — значит копии свежие, и целый час трогать его незачем. Не ответил
        // (не запущен, раздел ещё не расшифрован, просьба не записалась) — пробуем снова
        // скоро: иначе после перезагрузки журнал стоит до следующего часа.
        val answered = when {
            !asked -> false
            !wait -> true
            else -> resp.lastModifiedSafe() > before
        }
        p.edit().putLong(K_NEXT_TRY, now + if (answered) MIN_GAP_MS else RETRY_GAP_MS).apply()
        if (total > 0) log.add("Мост: добавлено $total сообщений в журнал")
        return total
    }

    /** Ждём, пока модуль перепишет response. Нет ответа — работаем по прошлым копиям. */
    private fun waitForResponse(resp: File, before: Long, budget: Long) {
        val until = System.currentTimeMillis() + budget
        while (System.currentTimeMillis() < until) {
            if (resp.lastModifiedSafe() > before) return
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun File.lastModifiedSafe(): Long = try { lastModified() } catch (_: Exception) { 0L }

    private fun importOne(context: Context, db: File, channel: String, key: String,
                          p: android.content.SharedPreferences): Int? {
        if (!db.exists()) return null
        val added = WaImporter.import(context, db, channel, since(p, key))
        if (added < 0) return null
        // Водяной знак двигаем по времени копии, а не по последнему сообщению: сообщений
        // может не быть вовсе, и тогда следующий импорт снова перебирал бы 120 дней.
        p.edit().putLong(key, db.lastModifiedSafe()).apply()
        return added
    }

    private fun importTelegram(context: Context, db: File,
                               p: android.content.SharedPreferences): Int? {
        if (!db.exists()) return null
        val added = TgImporter.import(context, db, since(p, K_TG))
        if (added < 0) return null
        p.edit().putLong(K_TG, db.lastModifiedSafe()).apply()
        return added
    }
}
