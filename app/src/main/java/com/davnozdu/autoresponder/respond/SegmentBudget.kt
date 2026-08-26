package com.davnozdu.autoresponder.respond

/**
 * Расчёт бюджета символов для ответа в пределах N SMS-сегментов.
 *
 * GSM-7 (латиница без диакритики): 160 в одном, 153 в каждом при конкатенации.
 * UCS-2 (кириллица, чешская диакритика, эмодзи): 70 в одном, 67 при конкатенации.
 * Поэтому русский/чешский помещают вдвое меньше символов, чем английский.
 */
object SegmentBudget {

    // Базовый набор GSM 03.38 (7-bit). Символы вне него → всё сообщение уходит в UCS-2.
    private val GSM7 = (
        "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
        "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    ).toSet()
    // Символы, занимающие в GSM-7 два места (расширенная таблица).
    private val GSM7_EXT = "^{}\\[~]|€".toSet()

    fun isGsm7(text: String): Boolean =
        text.all { it in GSM7 || it in GSM7_EXT }

    /** Максимум символов, чтобы уложиться в maxSegments (грубая оценка для промпта LLM). */
    fun charBudget(sampleLang: String, maxSegments: Int): Int {
        // По языку заранее знаем кодировку ответа.
        val ucs2 = sampleLang == "ru" || sampleLang == "cs"
        val per = if (ucs2) 67 else 153
        val single = if (ucs2) 70 else 160
        return if (maxSegments <= 1) single else per * maxSegments
    }

    /** Бюджет символов по ФАКТИЧЕСКОЙ кодировке текста (а не по угаданному языку). */
    fun budgetForText(text: String, maxSegments: Int): Int {
        val ucs2 = !isGsm7(text)
        val per = if (ucs2) 67 else 153
        val single = if (ucs2) 70 else 160
        return if (maxSegments <= 1) single else per * maxSegments
    }

    /** «Вес» символа в единицах кодировки: в GSM-7 расширенные символы занимают ДВЕ позиции. */
    private fun weight(c: Char, ucs2: Boolean): Int =
        if (!ucs2 && c in GSM7_EXT) 2 else 1

    /** Длина текста в единицах кодировки (а не в символах Kotlin). */
    fun encodedLength(text: String): Int {
        val ucs2 = !isGsm7(text)
        return text.sumOf { weight(it, ucs2) }
    }

    /** Жёсткая обрезка по границе слова под бюджет (кодировка определяется по тексту). */
    fun clampToBudget(text: String, maxSegments: Int): String {
        val budget = budgetForText(text, maxSegments)
        val ucs2 = !isGsm7(text)
        if (encodedLength(text) <= budget) return text
        // Идём по символам и считаем реальный вес; заодно не рвём суррогатную пару (эмодзи),
        // из-за чего в сообщении появлялся «битый» символ.
        var used = 0
        var end = 0
        while (end < text.length) {
            val c = text[end]
            val pairLen = if (Character.isHighSurrogate(c) && end + 1 < text.length) 2 else 1
            val w = if (pairLen == 2) (if (ucs2) 2 else 1) else weight(c, ucs2)
            if (used + w > budget) break
            used += w
            end += pairLen
        }
        val cut = text.substring(0, end)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > end * 3 / 4) cut.substring(0, lastSpace) else cut
    }
}
