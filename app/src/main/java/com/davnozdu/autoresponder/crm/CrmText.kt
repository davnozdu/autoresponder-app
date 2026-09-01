package com.davnozdu.autoresponder.crm

/**
 * Тексты ответов о заказе — шаблонами, не моделью.
 *
 * Смысл функции в том, чтобы одинаковый вопрос получал одинаковый и точный ответ:
 * мнительный клиент, спросивший трижды, трижды прочитает одно и то же и перестанет
 * спрашивать. Сочинённая моделью дата сделала бы ровно обратное.
 *
 * Тексты короткие намеренно: в SMS кириллицей в сегмент влезает 67 знаков.
 */
object CrmText {

    private fun l(lang: String) = when (lang.lowercase()) {
        "cs", "cz" -> "cs"
        "en" -> "en"
        else -> "ru"
    }

    /** «31.08» из «2026-08-31 14:20:00» — год в SMS лишний, речь всегда о недавнем. */
    private fun day(ts: String?): String {
        if (ts.isNullOrBlank() || ts.length < 10) return ""
        return ts.substring(8, 10) + "." + ts.substring(5, 7)
    }

    private fun title(r: CrmRecord, lang: String): String {
        val dev = r.device.trim()
        val num = r.number.trim()
        return when {
            dev.isNotBlank() && num.isNotBlank() -> "$dev ($num)"
            dev.isNotBlank() -> dev
            num.isNotBlank() -> num
            else -> when (l(lang)) { "cs" -> "reklamace"; "en" -> "claim"; else -> "рекламация" }
        }
    }

    /** Статус одной записи + предложение позвать мастера. */
    fun status(r: CrmRecord, lang: String): String {
        val t = title(r, lang)
        // Строку «последний статус» показываем, только если она добавляет что-то к этапу:
        // «В работе. Последний статус: В работе» — шум, ради которого не стоит тратить сегмент.
        val extra = if (r.lastLabel != null && !r.lastLabel.equals(r.label, true)) {
            val d = day(r.lastAt)
            when (l(lang)) {
                "cs" -> " Poslední změna: ${r.lastLabel}" + (if (d.isNotBlank()) " ($d)." else ".")
                "en" -> " Latest update: ${r.lastLabel}" + (if (d.isNotBlank()) " ($d)." else ".")
                else -> " Последний статус: ${r.lastLabel}" + (if (d.isNotBlank()) " ($d)." else ".")
            }
        } else ""

        return when (l(lang)) {
            "cs" -> "$t — ${r.label}.$extra Až bude hotovo, ozveme se. " +
                    "Chcete odpověď od technika? Napište ANO."
            "en" -> "$t — ${r.label}.$extra We will message you when it is ready. " +
                    "Want a reply from the technician? Write YES."
            else -> "$t — ${r.label}.$extra Как будет готово, вы получите сообщение. " +
                    "Нужен ответ мастера — напишите ДА."
        }
    }

    /** Несколько активных записей — просим уточнить. */
    fun choose(records: List<CrmRecord>, lang: String): String {
        val list = records.take(3).joinToString("; ") { "${title(it, lang)} — ${it.label}" }
        return when (l(lang)) {
            "cs" -> "Máte více zakázek: $list. Které se týká dotaz? Napište číslo zakázky nebo zařízení."
            "en" -> "You have several orders: $list. Which one? Reply with the order number or the device."
            else -> "У вас несколько заказов: $list. Про какой спрашиваете? " +
                    "Напишите номер заказа или устройство."
        }
    }

    /** Уточнить не вышло — отдаём всё разом и больше не переспрашиваем. */
    fun all(records: List<CrmRecord>, lang: String): String {
        val list = records.take(3).joinToString("; ") { "${title(it, lang)} — ${it.label}" }
        return when (l(lang)) {
            "cs" -> "Stav zakázek: $list. Chcete odpověď od technika? Napište ANO."
            "en" -> "Order status: $list. Want a reply from the technician? Write YES."
            else -> "Статусы заказов: $list. Нужен ответ мастера — напишите ДА."
        }
    }

    fun asked(lang: String): String = when (l(lang)) {
        "cs" -> "Předal jsem dotaz technikovi. Odpoví v pracovní době nebo hned, jak to půjde."
        "en" -> "Passed your question to the technician. He will reply during working hours or as soon as possible."
        else -> "Передал вопрос мастеру. Он ответит в рабочее время или при первой возможности."
    }

    fun askFailed(lang: String): String = when (l(lang)) {
        "cs" -> "Dotaz se teď nepodařilo předat. Zkuste to prosím za chvíli."
        "en" -> "Could not pass the question right now. Please try again shortly."
        else -> "Не удалось передать вопрос прямо сейчас. Напишите ещё раз чуть позже."
    }

    fun askClosed(lang: String): String = when (l(lang)) {
        "cs" -> "K této zakázce už zprávy nepřijímáme. Ozvěte se nám prosím přímo."
        "en" -> "This order no longer accepts messages. Please contact us directly."
        else -> "По этому заказу сообщения уже не принимаются. Свяжитесь с нами напрямую."
    }
}
