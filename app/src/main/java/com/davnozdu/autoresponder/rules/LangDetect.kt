package com.davnozdu.autoresponder.rules

/**
 * Лёгкое офлайн-определение языка. Онлайн язык уточняет сама LLM.
 *
 * Порядок: кириллица → ru; чешская диакритика → cs; частые чешские слова → cs;
 * латиница → en; ничего не понятно → язык по умолчанию (у мастерской чешский).
 *
 * Шаг со словами нужен потому, что по-чешски часто пишут без диакритики — «kdy bude
 * hotovo» по одним буквам неотличимо от английского, и клиент получал бы ответ
 * на чужом языке.
 */
object LangDetect {
    private val CZECH = "áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ".toSet()

    /** Слова, которых в английском нет, а в чешском сообщении в мастерскую они обычны. */
    private val CZECH_WORDS = setOf(
        "kdy", "bude", "hotovo", "hotova", "hotový", "hotovy", "dobry", "dobrý", "den",
        "prosim", "prosím", "dekuji", "děkuji", "diky", "díky", "objednavka", "objednávka",
        "oprava", "opravu", "opravy", "zakazka", "zakázka", "zakazku", "muj", "můj",
        "mate", "máte", "jeste", "ještě", "uz", "už", "kolik", "stoji", "stojí",
        "telefonu", "notebooku", "pocitac", "počítač", "vyzvednout", "ahoj", "dobre", "dobře")

    fun detect(text: String?, default: String): String {
        if (text.isNullOrBlank()) return default
        if (text.any { it in 'Ѐ'..'ӿ' }) return "ru"
        if (text.any { it in CZECH }) return "cs"

        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.any { it in CZECH_WORDS }) return "cs"

        return if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) "en" else default
    }
}
