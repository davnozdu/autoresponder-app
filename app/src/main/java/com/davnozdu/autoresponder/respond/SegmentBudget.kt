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

    /** Жёсткая обрезка по границе слова под бюджет (кодировка определяется по тексту). */
    fun clampToBudget(text: String, maxSegments: Int): String {
        val budget = budgetForText(text, maxSegments)
        if (text.length <= budget) return text
        val cut = text.substring(0, budget)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > budget * 3 / 4) cut.substring(0, lastSpace) else cut
    }
}
