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

    /**
     * Номер в списке исключений (Избранные) — сравнение по последним 9 цифрам.
     * Запись с маской (`*`, `?`) сопоставляется как шаблон: и с исходной строкой,
     * и с одними цифрами («+420*» или «*4567»).
     */
    fun isExcluded(raw: String?, excluded: List<String>): Boolean {
        if (excluded.isEmpty()) return false
        val plain = raw?.trim().orEmpty()
        if (plain.isEmpty()) return false
        val tail = digitsTail(plain)
        val digits = plain.filter { it.isDigit() }
        return excluded.any { entry ->
            val e = entry.trim()
            when {
                e.isEmpty() -> false
                NameMatch.hasWildcard(e) ->
                    NameMatch.matchesWildcard(plain, e) ||
                    (digits.isNotEmpty() &&
                     NameMatch.matchesWildcard(digits, e.filter { !it.isWhitespace() }))
                else -> tail != null && digitsTail(e) == tail
            }
        }
    }

    private fun digitsTail(raw: String?): String? {
        val d = raw?.filter { it.isDigit() } ?: return null
        if (d.isEmpty()) return null
        return if (d.length <= 9) d else d.takeLast(9)  // хвост номера, без учёта кода/формата
    }

    /**
     * Каноническая запись для хранения: номер — «+» и только цифры, без пробелов, скобок и
     * дефисов («+420 608 210 867» → «+420608210867»). Имя и маска возвращаются как есть:
     * маска сравнивается и с исходной строкой, поэтому её формат менять нельзя.
     *
     * «+» подставляется, только если код страны действительно есть: «00420…» → «+420…»,
     * «420 608 210 867» → «+420608210867». Местный номер (транковый ноль в начале или меньше
     * 10 цифр) остаётся без «+» — иначе получился бы несуществующий международный номер.
     */
    fun canonical(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || NameMatch.hasWildcard(t) || !looksLikeNumber(t)) return t
        val s = t.replace(Regex("[\\s\\-().]"), "")
        return when {
            s.startsWith("+") -> s
            s.startsWith("00") -> "+" + s.substring(2)
            s.startsWith("0") -> s                       // местный формат с транковым нулём
            s.length >= 10 -> "+$s"                      // код страны есть — дописываем «+»
            else -> s
        }
    }

    /** Похоже ли на телефонный номер: только цифры и разделители, минимум 8 цифр. */
    fun looksLikeNumber(raw: String?): Boolean {
        val t = raw?.trim() ?: return false
        if (t.isEmpty()) return false
        if (!t.all { it.isDigit() || it in "+()- ." }) return false
        return t.count { it.isDigit() } >= 8
    }

    /**
     * Один ли это номер, записанный по-разному («+31 6 1234 5678» и «+31612345678»).
     * Сравниваем по последним 9 цифрам — код страны и форматирование отличаются у разных
     * источников (мессенджер, книга контактов, ручной ввод).
     */
    fun sameNumber(a: String?, b: String?): Boolean {
        if (!looksLikeNumber(a) || !looksLikeNumber(b)) return false
        val ta = digitsTail(a) ?: return false
        val tb = digitsTail(b) ?: return false
        return ta == tb
    }

    /** Буквенный отправитель (Sberbank, Google...) — отвечать нельзя. */
    fun isAlphanumericSender(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        return raw.any { it.isLetter() }
    }
}
