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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Channel { MESSAGES, WHATSAPP, TELEGRAM }

/** Обработка входящих сообщений мессенджеров (RCS/WhatsApp/Telegram) через уведомления. */
object NotifResponder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(context: Context, sbn: StatusBarNotification, sender: String, text: String,
               channel: Channel, isGroup: Boolean, hasReply: Boolean) {
        val app = context.applicationContext
        scope.launch { process(app, sbn, sender, text, channel, isGroup, hasReply) }
    }

    private suspend fun process(context: Context, sbn: StatusBarNotification, sender: String,
                        text: String, channel: Channel, isGroup: Boolean, hasReply: Boolean) {
        val s = Settings(context)
        val log = EventLog(context)
        if (!s.enabled || !s.respondSms) return
        if (text.isBlank()) return
        if (isGroup) { log.add("NOTIF ${sender.take(16)} — группа, пропуск"); return }
        // WhatsApp/Telegram без кнопки ответа = канал/рассылка/не-сообщение → пропуск.
        if (channel != Channel.MESSAGES && !hasReply) return
        if (ClosedState.reason(context, s) == null) return  // открыто — молчим

        val tag = channel.name.lowercase()

        // Ключ для лимита/анти-петли и правила по номеру (только для Messages).
        val key: String
        val number: String?
        if (channel == Channel.MESSAGES) {
            number = extractNumber(sender)
            if (number == null) { log.add("NOTIF[$tag] ${sender.take(16)} — контакт без номера, пропуск"); return }
            if (!PhoneMask.matches(number, s.allowedPrefixes)) { log.add("NOTIF[$tag] $number — не под маску, пропуск"); return }
            SkipPolicy.reason(context, number, s, isCall = false)?.let { log.add("NOTIF[$tag] $number — $it, пропуск"); return }
            key = PhoneMask.normalize(number)!!
        } else {
            // WhatsApp/Telegram: номера нет, маска не применяется; ключ по имени.
            number = null
            key = "$tag:${sender.trim().lowercase()}"
        }

        val store = ReplyStore(context)
        if (!store.canReply(key, s.maxReplies, s.timeoutHours)) { log.add("NOTIF[$tag] $key — лимит, пропуск"); return }
        // Для Messages ждём: обычное SMS за это время застолбит SmsReceiver (уйдёт с выбранной SIM),
        // а до сюда дойдёт только настоящий RCS (у него события приёмника нет).
        if (channel == Channel.MESSAGES) delay(2000)
        val dedupKey = if (channel == Channel.MESSAGES) text else "$tag|$text"
        if (!Dedup.claim(dedupKey)) { log.add("NOTIF[$tag] $key — обычное SMS/дубль, пропуск"); return }

        val returning = store.count(key) > 0
        val reply = Responder.composeReply(context, s, text, Kind.SMS, returning)

        // Ответ через кнопку уведомления (в тот же тред: RCS/WhatsApp/Telegram).
        if (tryRemoteInputReply(context, sbn, reply)) {
            store.markReplied(key, s.timeoutHours)
            log.add("NOTIF[$tag] $key — ответ (#${store.count(key)}/${s.maxReplies}): $reply")
            return
        }
        // Запасной SMS только для Messages (есть номер).
        if (channel == Channel.MESSAGES && number != null) {
            val subId = SimUtil.resolveSubId(context, s.smsSlot)
            val segs = SmsSender.send(context, key, reply, subId)
            if (segs >= 0) { store.markReplied(key, s.timeoutHours); log.add("NOTIF[$tag] $key — запасной SMS ($segs сег): $reply"); return }
        }
        log.add("NOTIF[$tag] $key — ответить не удалось (нет кнопки Reply)")
    }

    private fun tryRemoteInputReply(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        return try {
            val n = sbn.notification ?: return false
            val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: Exception) {
            EventLog(context).add("NOTIF remoteInput error: ${e.message}"); false
        }
    }

    private fun extractNumber(sender: String): String? {
        val c = sender.trim()
        val digits = c.count { it.isDigit() }
        val looksNumber = digits >= 6 && c.all { it.isDigit() || it in "+()- " }
        return if (looksNumber) PhoneMask.normalize(c) else null
    }

    /** (отправитель, текст, группа?) из уведомления. */
    fun extract(n: Notification): Triple<String, String, Boolean>? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            val who = last.person?.name?.toString() ?: style.conversationTitle?.toString() ?: "?"
            val body = last.text?.toString() ?: ""
            return Triple(who, body, style.isGroupConversation)
        }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: return null
        return Triple(title, text, false)
    }
}
