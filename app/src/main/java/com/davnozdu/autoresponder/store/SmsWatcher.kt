package com.davnozdu.autoresponder.store

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.davnozdu.autoresponder.data.EventLog

/**
 * Слежение за системной базой SMS — чтобы журнал вёлся постоянно, а не по кнопке.
 *
 * Входящие SMS ловит [com.davnozdu.autoresponder.sms.SmsReceiver], но исходящих у него нет:
 * когда владелец пишет клиенту сам из приложения «Сообщения», ни broadcast, ни уведомления
 * не приходят. В журнале оставалась половина разговора, и LLM отвечала клиенту так, будто
 * переписки не было — при том, что клиент отвечал именно на вчерашнее письмо владельца.
 *
 * `content://sms` меняется на каждую отправку и доставку, поэтому обработку откладываем:
 * при отправке длинного сообщения провайдер дёргается несколько раз подряд.
 */
object SmsWatcher {

    private const val K_WM = "sms_wm"
    /** Пауза после последнего изменения провайдера — чтобы не читать базу по три раза. */
    private const val DEBOUNCE_MS = 4_000L
    /** Глубина первого прохода, если водяного знака ещё нет. */
    private const val FIRST_RUN_DAYS = 30L

    /**
     * Отступ назад от водяного знака. Провайдер ставит `date` отправки, а строку добавляет
     * позже: сообщение, записанное сразу после нашего запроса, но с более ранней отметкой,
     * иначе не вернулось бы никогда. Перекрытие безвредно — повторы отсекает дедуп.
     */
    private const val OVERLAP_MS = 600_000L

    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    @Volatile private var pending: Runnable? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences("autoresp_bridge", Context.MODE_PRIVATE)

    /** Идемпотентно: слушатель уведомлений переподключается, и start вызовется снова. */
    @Synchronized
    fun start(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = schedule(app)
        }
        try {
            app.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, obs)
            observer = obs
            EventLog(app).add("Журнал SMS: слежение включено")
            schedule(app)   // первый проход — подобрать то, что пропущено, пока процесс был мёртв
        } catch (e: Exception) {
            EventLog(app).add("Журнал SMS: слежение не включилось (${e.message})")
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val obs = observer ?: return
        try { context.applicationContext.contentResolver.unregisterContentObserver(obs) } catch (_: Exception) {}
        observer = null
    }

    private fun schedule(context: Context) {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { Thread { drain(context) }.start() }
        pending = r
        main.postDelayed(r, DEBOUNCE_MS)
    }

    /** Разовый проход — вызывается и по расписанию, и перед составлением ответа. */
    fun drain(context: Context) {
        val app = context.applicationContext
        try {
            val p = prefs(app)
            val wm = p.getLong(K_WM, 0L)
            val since = if (wm > 0) (wm - OVERLAP_MS).coerceAtLeast(0L) else
                System.currentTimeMillis() - FIRST_RUN_DAYS * 86_400_000L
            val n = Importer.importSmsSince(app, since)
            // Знак двигаем на момент чтения, а не на время последней записи: SMS могло
            // не быть вовсе, и тогда каждый проход перебирал бы весь месяц заново.
            p.edit().putLong(K_WM, System.currentTimeMillis()).apply()
            if (n > 0) {
                PersonThreads.invalidate()
                EventLog(app).add("Журнал SMS: добавлено $n записей")
            }
        } catch (e: Exception) {
            EventLog(app).add("Журнал SMS: ошибка добора (${e.message})")
        }
    }
}
