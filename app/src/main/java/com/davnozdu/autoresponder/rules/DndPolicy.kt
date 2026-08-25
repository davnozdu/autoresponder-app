package com.davnozdu.autoresponder.rules

import android.app.NotificationManager
import android.content.Context

/**
 * Определяет, пропустил бы текущий режим «Не беспокоить» этого отправителя
 * (звонок или SMS). Если да — авто-ответ давать НЕ нужно.
 * Требует доступ к политике уведомлений; если его нет — возвращает false
 * (тогда работают запасные правила по звёздным/книжным контактам).
 */
object DndPolicy {

    fun wouldPassThrough(context: Context, number: String?, isCall: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val filter = nm.currentInterruptionFilter
        // DND выключен — здесь не решаем (закрыто может быть по расписанию).
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) return false
        // Полная тишина / только будильники — не пропускает никого из людей.
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) return false
        if (!nm.isNotificationPolicyAccessGranted) return false

        val policy = try { nm.notificationPolicy } catch (e: Exception) { return false }
        val cats = policy.priorityCategories
        return if (isCall) {
            val allowCalls = cats and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0
            allowCalls && senderMatches(context, number, policy.priorityCallSenders)
        } else {
            val allowMsg = cats and NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES != 0
            allowMsg && senderMatches(context, number, policy.priorityMessageSenders)
        }
    }

    private fun senderMatches(context: Context, number: String?, senders: Int): Boolean =
        when (senders) {
            NotificationManager.Policy.PRIORITY_SENDERS_ANY -> true
            NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS -> ContactUtil.isKnownContact(context, number)
            NotificationManager.Policy.PRIORITY_SENDERS_STARRED -> ContactUtil.isStarred(context, number)
            else -> false
        }
}
