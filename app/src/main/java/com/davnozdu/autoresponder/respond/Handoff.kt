package com.davnozdu.autoresponder.respond

import android.content.Context
import android.net.Uri
import com.davnozdu.autoresponder.rules.ContactUtil
import com.davnozdu.autoresponder.rules.PhoneMask
import com.davnozdu.autoresponder.store.RuntimeDb

object Handoff {
    fun identity(who: String, channel: String): String =
        if (PhoneMask.looksLikeNumber(who)) "phone:${PhoneMask.normalize(who)}"
        else "$channel:${who.trim().lowercase()}"

    fun key(context: Context, who: String, channel: String) = RuntimeDb.get(context).canonical(identity(who, channel))

    /** Only explicit tel/contact URIs link channels; never match display names. */
    fun bind(context: Context, who: String, channel: String, uris: List<String>) {
        val phones = uris.flatMap { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            when {
                uri?.scheme == "tel" -> listOfNotNull(PhoneMask.normalize(uri.schemeSpecificPart))
                uri?.scheme == "content" && uri.authority == "com.android.contacts" -> ContactUtil.numbersForContactUri(context, uri)
                else -> emptyList()
            }
        }.mapNotNull { PhoneMask.normalize(it) }.distinct()
        // Multiple numbers are ambiguous unless already linked by a single verified number.
        if (phones.size == 1) RuntimeDb.get(context).bind(identity(who, channel), identity(phones.single(), "sms"))
    }
    fun pause(context: Context, who: String, channel: String, minutes: Int) {
        val now = System.currentTimeMillis()
        val until = if (minutes == 0) Long.MAX_VALUE else now + minutes * 60_000L
        RuntimeDb.get(context).setControl(key(context, who, channel), until, now)
        if (PhoneMask.looksLikeNumber(who)) com.davnozdu.autoresponder.store.HistoryDb.get(context).smsHoldRemove(PhoneMask.normalize(who) ?: who)
    }
    fun resume(context: Context, who: String, channel: String) =
        RuntimeDb.get(context).setControl(key(context, who, channel), 0, System.currentTimeMillis())

    fun blocked(context: Context, who: String, channel: String, receivedAt: Long): Boolean {
        val c = RuntimeDb.get(context).control(key(context, who, channel)) ?: return false
        return blocked(c.until, c.cutoff, receivedAt, System.currentTimeMillis())
    }
    fun blocked(until: Long, cutoff: Long, receivedAt: Long, now: Long): Boolean =
        until > now || receivedAt <= maxOf(until, cutoff)

    fun label(context: Context, who: String, channel: String): String? {
        val c = RuntimeDb.get(context).control(key(context, who, channel)) ?: return null
        if (c.until <= System.currentTimeMillis()) return null
        return if (c.until == Long.MAX_VALUE) "Вы отвечаете сами · до вашего включения"
        else "Вы отвечаете сами · до " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(c.until))
    }
}
