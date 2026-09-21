package com.davnozdu.autoresponder.crm

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.NetworkUtil
import com.davnozdu.autoresponder.rules.ClosedState

/**
 * Прогрев кеша CRM в ОЗУ.
 *
 * Кеш ответов живёт только в памяти — на флеш он не пишется намеренно, ресурс телефона
 * расходуемый. Расплата в том, что после запуска процесса работать нечем, и первый же
 * звонок стоил бы похода в сеть посреди звонка. Поэтому один раз при старте забираем
 * записи по всем номерам реестра и дальше отвечаем из памяти.
 *
 * Ходим умно: нет сети — не ходим вовсе; ночью не ходим, потому что работы не ведутся
 * и статусы не меняются. Исключение одно — пустой кеш, без него работать не из чего.
 *
 * Правило касается ТОЛЬКО фонового прогрева. Запрос по настоящему событию (клиент
 * написал или позвонил) идёт всегда, иначе ночью робот не ответит про заказ.
 */
object CrmPrefetch {
    private const val MAX = 50
    @Volatile private var done = false

    fun onStart(context: Context) {
        if (done) return
        done = true
        val app = context.applicationContext
        com.davnozdu.autoresponder.respond.EventQueue.submit { refresh(app) }
    }

    private fun refresh(context: Context) {
        val s = Settings(context)
        val working = ClosedState.reason(context, s) == null
        val ok = CrmSyncPolicy.shouldSync(
            crmReady = s.crmReady,
            online = NetworkUtil.isOnline(context),
            working = working,
            coldCache = CrmFlow.cachedCount() == 0,
            sinceLastMs = CrmRoster.sinceConfirmed(),
            intervalMs = CrmRoster.SYNC_INTERVAL_MS)
        if (!ok) return
        if (!CrmRoster.sync(context, force = true)) return
        val phones = CrmRoster.numbers(context).take(MAX)
        phones.forEach { CrmFlow.warm(context, it) }
        EventLog(context).add("CRM: кеш прогрет по ${CrmFlow.cachedCount()} из ${phones.size} номеров")
    }
}
