package com.davnozdu.autoresponder.store

import android.content.Context
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.PhoneMask

/** Правила сохранения истории. */
object HistoryLogger {

    /** Реальный телефонный номер (не короткий, не сервис/буквенный). */
    private fun isRealNumber(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        if (PhoneMask.isAlphanumericSender(raw)) return false
        val digits = raw.count { it.isDigit() }
        return digits >= 8
    }

    /**
     * channel: sms|rcs|call → нужен реальный номер; whatsapp|telegram → по имени (identity=sender).
     * direction: in|out
     *
     * @param ts время события. По умолчанию «сейчас», но у сообщения из уведомления есть
     *   собственная отметка (`sbn.postTime` или время сообщения в MessagingStyle), и она
     *   может быть заметно раньше: систему перезагрузили, слушатель переподключился и отдал
     *   уведомления часовой давности. С «сейчас» такая запись встала бы в конец переписки и
     *   перепутала LLM порядок разговора.
     */
    fun record(context: Context, identity: String?, channel: String, direction: String, body: String,
               auto: Boolean = false, ts: Long = System.currentTimeMillis()) {
        if (identity.isNullOrBlank()) return
        val app = context.applicationContext
        val db = HistoryDb.get(app)
        // Тот же текст мог прийти другим путём — от root-моста с ключом-номером вместо
        // имени и со своим временем. По ключу такой дубль не поймать, только по содержимому
        // и в пределах веток одного человека. Журнал звонков сюда не входит: у него один
        // источник, а два звонка подряд с одинаковой подписью — обычное дело.
        if (channel != "call" &&
            db.existsNear(PersonThreads.keysFor(app, identity), channel, direction, body, ts)) return
        when (channel) {
            "sms", "rcs", "call" -> {
                if (!isRealNumber(identity)) return
                val num = PhoneMask.normalize(identity) ?: identity
                val name = ContactUtil.nameFor(app, num)
                db.insert(num, name, channel, direction, body, ts, auto = auto)
            }
            else -> { // whatsapp/telegram: идентичность = имя отправителя
                db.insert(identity.trim(), identity.trim(), channel, direction, body, ts, auto = auto)
            }
        }
        // Через record проходят все каналы — отсюда и тикает счётчик живого уведомления DND.
        com.davnozdu.autoresponder.notif.DndStats.onEvent(app, channel, direction, auto)
    }
}
