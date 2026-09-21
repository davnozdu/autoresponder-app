package com.davnozdu.autoresponder.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.crm.CrmFlow
import com.davnozdu.autoresponder.crm.CrmText
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.SmsSender
import com.davnozdu.autoresponder.rules.SimUtil
import com.davnozdu.autoresponder.store.HistoryLogger

/**
 * Кнопки карточки звонящего. Два шага: первое нажатие спрашивает, второе отправляет.
 *
 * Текст клиенту не сочиняется — берётся CrmText.all(), тот же, которым робот отвечает
 * на вопрос о статусе, и на языке клиента из карточки CRM, а не на языке мастера.
 */
class CallerCardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(CallerCardNotifier.EXTRA_NUMBER) ?: return
        val app = context.applicationContext
        when (intent.action) {
            CallerCardNotifier.ACTION_DISMISS -> CallerCardNotifier.cancel(app, number)
            CallerCardNotifier.ACTION_CONFIRM -> {
                val pending = goAsync()
                Thread {
                    try {
                        CallerCardNotifier.show(app, number,
                            runCatching { CrmFlow.lookup(app, listOf(number)) }.getOrNull(),
                            confirming = true)
                    } finally { pending.finish() }
                }.start()
            }
            CallerCardNotifier.ACTION_SEND -> {
                val pending = goAsync()
                Thread { try { send(app, number) } finally { pending.finish() } }.start()
            }
        }
    }

    private fun send(context: Context, number: String) {
        val log = EventLog(context)
        // Реестр обновляется раз в 15 минут, и суточный кеш мог бы отдать вчерашний этап.
        // Клиенту уходит настоящее сообщение — берём свежее, а не то, что лежит в памяти.
        CrmFlow.invalidate(number)
        val lookup = runCatching { CrmFlow.lookup(context, listOf(number)) }.getOrNull()
        if (lookup == null || lookup.records.isEmpty()) {
            log.add("КАРТОЧКА $number — CRM не ответила, статус не отправлен")
            CallerCardNotifier.sent(context, number, "CRM не ответила — ничего не отправлено")
            return
        }
        val s = Settings(context)
        val text = CrmText.all(lookup.records, lookup.lang)
        val subId = SimUtil.resolveSubId(context, s.slotForNumber(number))
        // limitKey пуст намеренно: это ответ мастера, а не робота. Лимит автоответов он не
        // тратит, а в журнал пишется как сообщение мастера — тогда подготовленный автоответ
        // этому клиенту отменяется, и робот не скажет то же самое второй раз.
        val segs = SmsSender.send(context, number, text, subId)
        if (segs >= 0) {
            HistoryLogger.record(context, number, "sms", "out", text, auto = false)
            log.add("КАРТОЧКА $number — статус отправлен вручную ($segs сег): $text")
            CallerCardNotifier.sent(context, number, text)
        } else {
            log.add("КАРТОЧКА $number — отправить не удалось")
            CallerCardNotifier.sent(context, number, "Отправить не удалось — проверьте SIM и сеть")
        }
    }
}
