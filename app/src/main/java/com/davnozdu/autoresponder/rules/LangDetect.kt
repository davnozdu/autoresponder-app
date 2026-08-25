package com.davnozdu.autoresponder.rules

/**
 * Лёгкое офлайн-определение языка по алфавиту.
 * ru — кириллица; cs — латиница с чешской диакритикой; иначе en.
 * (Онлайн язык уточняет сама LLM.)
 */
object LangDetect {
    private val CZECH = "áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ".toSet()

    fun detect(text: String?, default: String): String {
        if (text.isNullOrBlank()) return default
        val hasCyrillic = text.any { it in 'Ѐ'..'ӿ' }
        if (hasCyrillic) return "ru"
        val hasCzech = text.any { it in CZECH }
        if (hasCzech) return "cs"
        val hasLatin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
        return if (hasLatin) "en" else default
    }
}
