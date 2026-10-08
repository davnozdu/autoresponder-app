package com.davnozdu.autoresponder.msgrec

import java.util.Locale

/** Conservative fallback for messenger call notifications without standard CallStyle extras. */
internal object IncomingCallPolicy {
    /** A notification replacement may change its action, but must still identify the caller. */
    fun sameCaller(original: String?, current: String?, sameAnswerAction: Boolean): Boolean {
        if (original != null && current != null) return original == current
        return sameAnswerAction
    }

    private val answers = setOf("answer", "accept", "answer call", "accept call", "ответить", "принять",
        "ответить на звонок", "принять звонок", "přijmout", "prijmout", "odpovědět", "prijať",
        "відповісти", "прийняти", "annehmen")
    private val declines = setOf("decline", "reject", "decline call", "reject call", "отклонить",
        "отклонить звонок", "odmítnout", "odmitnout", "відхилити", "ablehnen")

    fun isVideo(flag: Boolean, fields: String): Boolean = flag ||
        listOf("video", "видео", "відео").any { it in fields.lowercase(Locale.ROOT) }

    fun legacyAnswerIndex(callCategory: Boolean, callType: Int, video: Boolean, fields: String,
                          actionTitles: List<String>): Int? {
        // A typed ongoing/screening call must never fall through to label matching.
        if (!callCategory || callType != 0 || isVideo(video, fields)) return null
        val text = fields.lowercase(Locale.ROOT)
        if (listOf("outgoing", "исходящ", "odchoz", "вихідн").any { it in text }) return null
        val titles = actionTitles.map { it.lowercase(Locale.ROOT).trim().trim { c -> !c.isLetter() } }
        val candidates = titles.indices.filter { titles[it] in answers }
        if (candidates.size != 1) return null
        val incoming = listOf("incoming", "входящ", "příchoz", "prichoz", "вхідн", "eingehend")
            .any { it in text }
        if (!incoming && titles.none { it in declines }) return null
        return candidates.single()
    }

    fun endIndex(ongoing: Boolean, callType: Int, actionTitles: List<String>): Int? {
        if (!ongoing || callType == 1 || callType == 3) return null
        val ends = setOf("hang up", "hangup", "end", "end call", "disconnect", "завершить",
            "завершить звонок", "завершить вызов", "отбой", "ukončit", "ukoncit",
            "ukončit hovor", "ukoncit hovor", "beenden", "auflegen", "завершити")
        val candidates = actionTitles.indices.filter {
            actionTitles[it].lowercase(Locale.ROOT).trim().trim { c -> !c.isLetter() } in ends
        }
        return candidates.singleOrNull()
    }
}
