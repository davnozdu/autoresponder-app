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
import com.davnozdu.autoresponder.respond.EventQueue
import com.davnozdu.autoresponder.respond.SmsSender
import com.davnozdu.autoresponder.store.HistoryDb
import com.davnozdu.autoresponder.store.HistoryLogger
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.rules.SimUtil
import com.davnozdu.autoresponder.rules.SkipPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Channel { MESSAGES, MESSENGER }

/** Обработка входящих сообщений мессенджеров (RCS/WhatsApp/Telegram) через уведомления. */
object NotifResponder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(context: Context, sbn: StatusBarNotification, sender: String, text: String,
               channel: Channel, tag: String, isGroup: Boolean, hasReply: Boolean) {
        val app = context.applicationContext
        EventQueue.submit { process(app, sbn, sender, text, channel, tag, isGroup, hasReply) }
    }

    private suspend fun process(context: Context, sbn: StatusBarNotification, sender: String,
                        text: String, channel: Channel, tag: String, isGroup: Boolean, hasReply: Boolean) {
        val s = Settings(context)
        val log = EventLog(context)
        if (!s.enabled || !s.respondSms) return
        if (text.isBlank() || isPlaceholder(text)) { return }
        if (isGroup) { log.add("NOTIF ${sender.take(16)} — группа, пропуск"); return }
        // WhatsApp/Telegram без кнопки ответа = канал/рассылка/не-сообщение → пропуск.
        if (channel != Channel.MESSAGES && !hasReply) return
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
            number = null
            key = "$tag:${sender.trim().lowercase()}"
        }

        // История входящего RCS (мессенджеры пишутся в listener) — всегда.
        if (channel == Channel.MESSAGES) HistoryLogger.record(context, number, "rcs", "in", text)
        val inCh = if (channel == Channel.MESSAGES) "rcs" else tag
        val inId = if (channel == Channel.MESSAGES) number else sender

        // Чёрный список отвечает ВСЕГДА; обычные — только когда «закрыто».
        val bl = HistoryDb.get(context).blacklistMatch(
            if (channel == Channel.MESSAGES) number else null, sender)
        val closedReason = ClosedState.reason(context, s)
        if (bl != null) {
            val chOk = if (channel == Channel.MESSAGES) bl.onSms else bl.onMsgr
            if (!bl.viaLlm || !chOk) { log.add("NOTIF[$tag] $key — ЧС: без ответа"); return }
        }
        val forceReply = bl != null
        if (!forceReply && closedReason == null) return  // открыто — молчим
        val override = if (forceReply) bl.prompt else null

        val store = ReplyStore(context)
        if (!store.canReply(key, s.maxReplies, s.timeoutHours)) { log.add("NOTIF[$tag] $key — лимит, пропуск"); return }
        // Для Messages пауза: обычное SMS застолбит SmsReceiver, до сюда дойдёт только RCS.
        if (channel == Channel.MESSAGES) delay(2000)
        val dedupKey = if (channel == Channel.MESSAGES) "sms:$key:$text" else "$tag:$key"
        if (!Dedup.claim(dedupKey)) { log.add("NOTIF[$tag] $key — обычное SMS/дубль, пропуск"); return }

        val returning = store.count(key) > 0
        val reply = Responder.composeReply(context, s, text, Kind.SMS, returning, override, closedReason != null)

        // Ответ через кнопку уведомления (в тот же тред: RCS/WhatsApp/Telegram).
        if (tryRemoteInputReply(context, sbn, reply)) {
            store.markReplied(key, s.timeoutHours)
            HistoryLogger.record(context, inId, inCh, "out", reply)
            NotifListenerService.dismiss(sbn.key)
            log.add("NOTIF[$tag] $key — ответ (#${store.count(key)}/${s.maxReplies}): $reply")
            return
        }
        // Запасной SMS только для Messages (есть номер).
        if (channel == Channel.MESSAGES && number != null) {
            val subId = SimUtil.resolveSubId(context, s.smsSlot)
            val segs = SmsSender.send(context, key, reply, subId)
            if (segs >= 0) { store.markReplied(key, s.timeoutHours); HistoryLogger.record(context, key, "sms", "out", reply); log.add("NOTIF[$tag] $key — запасной SMS ($segs сег): $reply"); return }
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

    private val placeholderRe = Regex("^\\s*\\d+\\s+(new\\s+)?messages?\\s*$", RegexOption.IGNORE_CASE)
    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || placeholderRe.matches(t) ||
            t.equals("new message", true) || t.equals("Фото", true) || t.equals("Photo", true) ||
            t.equals("Видео", true) || t.equals("Video", true) || t.equals("Sticker", true) ||
            t.equals("GIF", true) || t.equals("Voice message", true)
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
            // Контекст: последние сообщения (входящие от клиента), новейшее — последним.
            val recent = style.messages.takeLast(4)
                .mapNotNull { it.text?.toString()?.trim()?.ifBlank { null } }
            val body = if (recent.size > 1) recent.joinToString("\n") else (last.text?.toString() ?: "")
            return Triple(who, body, style.isGroupConversation)
        }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: return null
        return Triple(title, text, false)
    }
}
