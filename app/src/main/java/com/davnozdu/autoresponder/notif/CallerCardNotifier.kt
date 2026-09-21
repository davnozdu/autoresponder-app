package com.davnozdu.autoresponder.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.text.HtmlCompat
import com.davnozdu.autoresponder.crm.CrmFlow
import com.davnozdu.autoresponder.crm.CrmLookup
import com.davnozdu.autoresponder.crm.CrmRoster
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.crm.CrmSyncPolicy
import com.davnozdu.autoresponder.respond.NetworkUtil
import com.davnozdu.autoresponder.rules.PhoneMask

/**
 * Карточка звонящего: кто это и что у него в работе.
 *
 * Показывается сразу из памяти — кеш CRM прогрет при запуске. Если его нет (процесс
 * только поднялся, а звонок уже идёт), карточка появляется с номером и дополняется,
 * когда CRM ответит: пустой экран во время звонка хуже неполного.
 */
object CallerCardNotifier {
    const val CHANNEL = "caller_card"
    const val ACTION_CONFIRM = "com.davnozdu.autoresponder.CARD_CONFIRM"
    const val ACTION_SEND = "com.davnozdu.autoresponder.CARD_SEND"
    const val ACTION_DISMISS = "com.davnozdu.autoresponder.CARD_DISMISS"
    const val EXTRA_NUMBER = "number"
    private const val GROUP = "caller_card"

    fun idFor(number: String) = 3100 + (number.hashCode() and 0xfff)

    /** Вызывается со screening-а звонка. Своего потока не заводит — ждёт фонового. */
    fun onIncoming(context: Context, rawNumber: String?) {
        val number = PhoneMask.normalize(rawNumber) ?: return
        val s = Settings(context)
        if (!s.crmReady) return
        // Номера нет в реестре — это ещё не «не клиент»: реестр обновляется раз в 15 минут,
        // а клиента могли завести минуту назад. Именно на этом карточка не появилась в первый
        // раз на живом звонке. Обходим порог, но не ETag: неизменившийся реестр вернётся 304.
        if (CrmSyncPolicy.shouldRefreshForCall(
                online = NetworkUtil.isOnline(context),
                inRoster = CrmRoster.contains(context, number),
                sinceLastMs = CrmRoster.sinceConfirmed(),
                minGapMs = 60_000L)) {
            CrmRoster.sync(context, ignoreThrottle = true)
        }
        if (!CrmRoster.shouldAsk(context, listOf(number))) return

        // Сначала заглушка с номером — она появляется мгновенно и не ждёт ничего.
        show(context, number, null)
        // Кеш прогрет при запуске, поэтому обычно возвращается сразу; холодный стоит
        // одного похода, и карточка дополнится, когда он закончится.
        val lookup = runCatching { CrmFlow.lookup(context, listOf(number)) }.getOrNull()
        if (lookup != null) show(context, number, lookup)
    }

    fun show(context: Context, number: String, lookup: CrmLookup?, confirming: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Карточка звонящего",
            NotificationManager.IMPORTANCE_HIGH))
        val card = CallerCard.render(lookup?.name?.ifBlank { null }, number, lookup)
        // Заглушка (данных ещё нет) приходит молча: всплывать должна карточка с именем и
        // заказом, а не строка с одним номером. Раньше было наоборот — ONLY_ALERT_ONCE
        // отдавал всплытие заглушке, а полезное приходило тихо и наверх уже не поднималось.
        val placeholder = lookup == null && !confirming
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(bold(if (confirming) "Отправить статус — ${card.title}?" else card.title))
            .setContentText(if (confirming) "Клиент получит SMS со статусом работ" else card.summary)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // Своя группа, чтобы Android не сгребал карточку в общую пачку приложения:
            // из неё её приходилось выискивать листанием.
            .setGroup(GROUP)
            .setWhen(System.currentTimeMillis())
            .setSilent(placeholder)
            .setOnlyAlertOnce(placeholder)
            .setTimeoutAfter(10 * 60_000L)
        if (card.details.isNotBlank() && !confirming) {
            b.setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle(bold(card.title))
                .bigText(bold("<b>${esc(card.summary)}</b><br>${esc(card.details).replace("\n", "<br>")}")))
        }
        if (lookup != null && lookup.records.isNotEmpty()) {
            if (confirming) {
                b.addAction(0, "Отправить", pi(context, ACTION_SEND, number))
                b.addAction(0, "Отмена", pi(context, ACTION_DISMISS, number))
            } else {
                // Один шаг до отправки живому клиенту — мало: карточка всплывает во время
                // звонка, телефон в руке, и промах пальцем стоил бы чужого SMS.
                b.addAction(0, "Сообщить статус по SMS", pi(context, ACTION_CONFIRM, number))
            }
        }
        runCatching { nm.notify(idFor(number), b.build()) }
    }

    /** Имя клиента нужно видеть сразу, поэтому оно жирным. Уведомления понимают часть HTML. */
    private fun bold(html: String): CharSequence =
        HtmlCompat.fromHtml(if (html.startsWith("<")) html else "<b>${esc(html)}</b>",
            HtmlCompat.FROM_HTML_MODE_LEGACY)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun sent(context: Context, number: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.notify(idFor(number), NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Статус отправлен")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60_000L)
                .build())
        }
    }

    fun cancel(context: Context, number: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(idFor(number))
    }

    private fun pi(context: Context, action: String, number: String): PendingIntent {
        val i = Intent(context, CallerCardReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("autoresp://caller/$number/$action")
            putExtra(EXTRA_NUMBER, number)
        }
        return PendingIntent.getBroadcast(context, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
