package com.davnozdu.autoresponder.rules

/** Нормализация номера и проверка по маске страны (список префиксов E.164). */
object PhoneMask {

    /** Приводит номер к виду +XXXXXXXX по возможности. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().replace(Regex("[\\s\\-()]"), "")
        when {
            s.startsWith("+") -> {}
            s.startsWith("00") -> s = "+" + s.substring(2)
            // local-формат без кода страны определить нельзя — оставляем как есть
        }
        return s
    }

    /**
     * true, если номер подходит под одну из разрешённых стран.
     * Номера без '+' (локальные, короткие, буквенные) считаем НЕ подходящими.
     */
    fun matches(raw: String?, allowedPrefixes: List<String>): Boolean {
        val n = normalize(raw) ?: return false
        if (!n.startsWith("+")) return false
        return allowedPrefixes.any { p ->
            val pp = if (p.startsWith("+")) p else "+$p"
            n.startsWith(pp)
        }
    }

    /** Буквенный отправитель (Sberbank, Google...) — отвечать нельзя. */
    fun isAlphanumericSender(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        return raw.any { it.isLetter() }
    }
}
