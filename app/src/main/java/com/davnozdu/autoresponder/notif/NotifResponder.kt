package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.ReplyStore
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.Dedup
import com.davnozdu.autoresponder.respond.Kind
import com.davnozdu.autoresponder.respond.Responder
import com.davnozdu.autoresponder.respond.SmsSender
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.rules.SimUtil
import com.davnozdu.autoresponder.rules.SkipPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Обработка входящих сообщений мессенджеров (RCS/Google Messages) через уведомления. */
object NotifResponder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(context: Context, sbn: StatusBarNotification, sender: String, text: String) {
        val app = context.applicationContext
        scope.launch { process(app, sbn, sender, text) }
    }

    private fun process(context: Context, sbn: StatusBarNotification, sender: String, text: String) {
        val s = Settings(context)
        val log = EventLog(context)
        if (!s.enabled || !s.respondSms) return
        if (text.isBlank()) return

        if (ClosedState.reason(context, s) == null) return  // открыто — молчим

        // Определяем номер отправителя (в уведомлении может быть имя контакта).
        val number = extractNumber(sender)
        if (number == null) {
            log.add("NOTIF ${sender.take(20)} — не номер (контакт), пропуск"); return
        }
        if (!PhoneMask.matches(number, s.allowedPrefixes)) {
            log.add("NOTIF $number — не под маску, пропуск"); return
        }
        SkipPolicy.reason(context, number, s, isCall = false)?.let {
            log.add("NOTIF $number — $it, пропуск"); return
        }
        val norm = PhoneMask.normalize(number)!!
        val store = ReplyStore(context)
        if (!store.canReply(norm, s.maxReplies, s.timeoutHours)) {
            log.add("NOTIF $norm — лимит, пропуск"); return
        }
        if (!Dedup.claim(text)) {
            log.add("NOTIF $norm — дубль, пропуск"); return
        }

        val returning = store.count(norm) > 0
        val reply = Responder.composeReply(context, s, text, Kind.SMS, returning)

        // Пытаемся ответить через кнопку уведомления (уйдёт в тот же тред — RCS).
        val viaNotif = tryRemoteInputReply(context, sbn, reply)
        if (viaNotif) {
            store.markReplied(norm, s.timeoutHours)
            log.add("NOTIF $norm — ответ через RCS [#${store.count(norm)}/${s.maxReplies}]")
            return
        }
        // Запасной вариант — обычное SMS с выбранной SIM.
        val subId = SimUtil.resolveSubId(context, s.smsSlot)
        val segs = SmsSender.send(context, norm, reply, subId)
        if (segs >= 0) {
            store.markReplied(norm, s.timeoutHours)
            log.add("NOTIF $norm — ответ запасным SMS [$segs сег.]")
        } else {
            log.add("NOTIF $norm — ОШИБКА ответа")
        }
    }

    /** Заполняет RemoteInput кнопки «Ответить» и отправляет её PendingIntent. */
    private fun tryRemoteInputReply(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        return try {
            val n = sbn.notification ?: return false
            val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            // некоторые клиенты требуют флаг «reply» в clip data — базовый путь обычно работает
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: Exception) {
            EventLog(context).add("NOTIF remoteInput error: ${e.message}")
            false
        }
    }

    /** Извлекает телефонный номер из строки отправителя; null, если это имя. */
    private fun extractNumber(sender: String): String? {
        val cleaned = sender.trim()
        // считаем номером, если состоит в основном из цифр/+/пробелов/скобок/дефисов
        val digits = cleaned.count { it.isDigit() }
        val looksNumber = digits >= 6 && cleaned.all { it.isDigit() || it in "+()- " }
        return if (looksNumber) PhoneMask.normalize(cleaned) else null
    }

    /** Достаёт (отправитель, текст) из уведомления мессенджера. */
    fun extract(n: Notification): Pair<String, String>? {
        // MessagingStyle — самый надёжный для Messages/WhatsApp/Telegram
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            val who = last.person?.name?.toString()
                ?: style.conversationTitle?.toString() ?: "?"
            val body = last.text?.toString() ?: ""
            return who to body
        }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: return null
        return title to text
    }
}
