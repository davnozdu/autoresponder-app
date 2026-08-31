package com.davnozdu.autoresponder.rules

/**
 * Сопоставление записи «Избранного» с отправителем из мессенджера.
 *
 * WhatsApp и Telegram кладут в уведомление НЕ никнейм и не @username, а имя, под которым
 * человек записан в телефонной книге («Алекс Лапшинский»); если контакта в книге нет —
 * номер в собственном формате приложения («+7 951 457-28-49»). Поэтому запись избранного
 * сравнивается как ИМЯ, а @username из мессенджера в уведомление не попадает вовсе.
 *
 * Дополнительно запись может быть маской: `*` — любой отрезок (в том числе пустой),
 * `?` — ровно один символ. Маска проверяется и по исходной строке, и по одним цифрам —
 * иначе «*2849» не совпало бы с «+7 951 457-28-49» из-за дефисов и пробелов.
 */
object NameMatch {

    fun hasWildcard(pattern: String): Boolean = pattern.any { it == '*' || it == '?' }

    /** Маска в стиле оболочки, без учёта регистра. */
    fun matchesWildcard(value: String, pattern: String): Boolean {
        val re = StringBuilder()
        for (ch in pattern) when (ch) {
            '*' -> re.append(".*")
            '?' -> re.append('.')
            else -> re.append(Regex.escape(ch.toString()))
        }
        return try {
            Regex(re.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .matches(value)
        } catch (e: Exception) { false }
    }

    /**
     * Отправитель подходит под запись избранного.
     *
     * Порядок: маска → точное имя (без учёта регистра и ведущего `@`) → один и тот же номер,
     * записанный по-разному.
     */
    fun matches(sender: String?, entry: String?): Boolean {
        val a = sender?.trim()?.removePrefix("@")?.replace(Regex("\\s+"), " ") ?: return false
        val e = entry?.trim()?.removePrefix("@")?.replace(Regex("\\s+"), " ") ?: return false
        if (a.isEmpty() || e.isEmpty()) return false
        if (hasWildcard(e)) {
            if (matchesWildcard(a, e)) return true
            // Маска по цифрам: формат номера у каждого мессенджера свой.
            val digits = a.filter { it.isDigit() }
            return digits.isNotEmpty() && matchesWildcard(digits, e.filter { it != ' ' })
        }
        if (a.equals(e, ignoreCase = true)) return true
        return PhoneMask.sameNumber(a, e)
    }

    /** Как запись будет сопоставляться — для подсказки в настройках. */
    fun describe(entry: String): String = when {
        hasWildcard(entry) -> "маска: * — любой текст, ? — один символ"
        PhoneMask.looksLikeNumber(entry) -> "номер — сравнение по цифрам, формат не важен"
        entry.count { it.isDigit() } >= 8 ->
            "сравнивается как ИМЯ (есть лишние символы). Для номера оставьте только цифры и +"
        else -> "имя из телефонной книги — точное совпадение"
    }
}
