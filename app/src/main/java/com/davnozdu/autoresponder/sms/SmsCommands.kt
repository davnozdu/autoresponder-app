package com.davnozdu.autoresponder.sms

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.SmsSender
import com.davnozdu.autoresponder.rules.AutoReplyState
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.store.HistoryDb

/**
 * Управление автоответчиком по SMS с доверенного номера.
 *
 * Телефон лежит в мастерской, а выключить автоответ или узнать, что происходит,
 * иногда нужно немедленно и не с него. Команда принимается ТОЛЬКО с номеров из
 * явного списка (сравнение по последним девяти цифрам, как везде) и только когда
 * функция включена — по умолчанию она выключена: SMS-отправителя подделать несложно,
 * и цена ошибки здесь — чужой доступ к настройкам.
 */
object SmsCommands {

    /** Команда целиком: одно слово, возможно с аргументом. Длинный текст — это не команда. */
    private const val MAX_LEN = 40

    fun isTrusted(s: Settings, sender: String?): Boolean {
        if (!s.smsCommandsOn || sender.isNullOrBlank()) return false
        val list = s.smsCommandNumbers
        if (list.isEmpty()) return false
        return list.any { PhoneMask.sameNumber(it, sender) }
    }

    /** Разбор команды. null — это не команда. */
    fun parse(body: String?): String? {
        val t = body?.trim()?.lowercase() ?: return null
        if (t.isEmpty() || t.length > MAX_LEN) return null
        return when (t.substringBefore(' ')) {
            "status", "статус" -> "status"
            "off", "stop", "выкл", "стоп" -> "off"
            "on", "start", "вкл", "старт" -> "on"
            "pause", "пауза" -> "pause"
            "digest", "сводка" -> "digest"
            else -> null
        }
    }

    /**
     * Выполнить команду. true — сообщение было командой и обычная обработка не нужна.
     */
    fun handle(context: Context, sender: String?, body: String?, subId: Int): Boolean {
        val s = Settings(context)
        if (!isTrusted(s, sender)) return false
        val cmd = parse(body) ?: return false
        val log = EventLog(context)
        val answer = when (cmd) {
            "off" -> { s.enabled = false; "Автоответ ВЫКЛЮЧЕН." }
            "on" -> { s.enabled = true; AutoReplyState.resume(context); "Автоответ ВКЛЮЧЁН." }
            "pause" -> { AutoReplyState.pauseUntilNextDnd(context); "Пауза до следующего включения «Не беспокоить»." }
            "digest" -> { com.davnozdu.autoresponder.notif.Digest.show(context, s); "Сводка показана на телефоне." }
            else -> status(context, s)
        }
        log.add("SMS-команда «$cmd» от ${sender ?: "?"} — выполнена")
        // Ответ уходит с той же карты, на которую пришла команда: иначе владелец
        // получает ответ с другого номера и не понимает, от кого он.
        SmsSender.send(context, sender!!, answer, subId)
        return true
    }

    private fun status(context: Context, s: Settings): String {
        val db = HistoryDb.get(context)
        val closed = ClosedState.reason(context, s) ?: "открыто"
        val paused = if (AutoReplyState.isPaused(context)) ", ПАУЗА" else ""
        val from = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val pending = db.needsAnswer(from).size
        val held = db.smsHoldCount()
        return buildString {
            append(if (s.enabled) "Вкл" else "ВЫКЛ").append(paused)
            append(", сейчас: ").append(closed)
            append(". За сутки авто: ").append(db.countAuto(from))
            append(", требуют ответа: ").append(pending)
            if (held > 0) append(", придержано до утра: ").append(held)
        }
    }
}
