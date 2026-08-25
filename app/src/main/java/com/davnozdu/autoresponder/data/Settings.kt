package com.davnozdu.autoresponder.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Синхронные настройки на SharedPreferences — удобно читать из сервисов/ресиверов.
 */
class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autoresp", Context.MODE_PRIVATE)

    // --- общий вкл/выкл ---
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    // --- когда считаем, что «закрыто» ---
    /** реагировать, если включён системный DND */
    var triggerOnDnd: Boolean
        get() = sp.getBoolean(K_TRIG_DND, true)
        set(v) = sp.edit().putBoolean(K_TRIG_DND, v).apply()

    /** реагировать по собственному расписанию (ночной режим) */
    var triggerOnSchedule: Boolean
        get() = sp.getBoolean(K_TRIG_SCHED, true)
        set(v) = sp.edit().putBoolean(K_TRIG_SCHED, v).apply()

    /** начало окна «закрыто», минуты от полуночи (напр. 21:00 = 1260) */
    var scheduleStartMin: Int
        get() = sp.getInt(K_SCHED_START, 21 * 60)
        set(v) = sp.edit().putInt(K_SCHED_START, v).apply()

    /** конец окна «закрыто», минуты от полуночи (напр. 09:00 = 540) */
    var scheduleEndMin: Int
        get() = sp.getInt(K_SCHED_END, 9 * 60)
        set(v) = sp.edit().putInt(K_SCHED_END, v).apply()

    // --- маска стран (список префиксов E.164, напр. "+420") ---
    var allowedPrefixes: List<String>
        get() = sp.getString(K_PREFIXES, "+420")!!
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(K_PREFIXES, v.joinToString(",")).apply()

    // --- Избранные: номера-исключения (автоответ НЕ применять) ---
    var excludedNumbers: List<String>
        get() = sp.getString(K_EXCLUDED, "")!!
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(K_EXCLUDED, v.joinToString(",")).apply()

    fun addExcluded(number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        val cur = excludedNumbers.toMutableList()
        if (cur.none { it.equals(n, true) }) { cur.add(n); excludedNumbers = cur }
    }
    fun removeExcluded(number: String) {
        excludedNumbers = excludedNumbers.filterNot { it.equals(number.trim(), true) }
    }

    /** не отвечать звёздным (избранным) контактам телефона */
    var excludeStarred: Boolean
        get() = sp.getBoolean(K_EXCL_STARRED, true)
        set(v) = sp.edit().putBoolean(K_EXCL_STARRED, v).apply()

    /** не отвечать любым контактам из телефонной книги */
    var excludeContacts: Boolean
        get() = sp.getBoolean(K_EXCL_CONTACTS, false)
        set(v) = sp.edit().putBoolean(K_EXCL_CONTACTS, v).apply()

    /** уважать приоритетных отправителей режима «Не беспокоить» */
    var respectDndPriority: Boolean
        get() = sp.getBoolean(K_RESPECT_DND, true)
        set(v) = sp.edit().putBoolean(K_RESPECT_DND, v).apply()

    // --- отвечать на звонки / SMS ---
    var respondCalls: Boolean
        get() = sp.getBoolean(K_RESP_CALLS, true)
        set(v) = sp.edit().putBoolean(K_RESP_CALLS, v).apply()

    var respondSms: Boolean
        get() = sp.getBoolean(K_RESP_SMS, true)
        set(v) = sp.edit().putBoolean(K_RESP_SMS, v).apply()

    // --- анти-флуд: не более N авто-ответов на номер, затем таймаут ---
    var maxReplies: Int
        get() = sp.getInt(K_MAX_REPLIES, 3)
        set(v) = sp.edit().putInt(K_MAX_REPLIES, v).apply()

    /** таймаут после исчерпания лимита, часы */
    var timeoutHours: Int
        get() = sp.getInt(K_TIMEOUT, 3)
        set(v) = sp.edit().putInt(K_TIMEOUT, v).apply()

    // --- максимум SMS-сегментов в одном ответе ---
    var maxSegments: Int
        get() = sp.getInt(K_MAX_SEG, 6)
        set(v) = sp.edit().putInt(K_MAX_SEG, v).apply()

    // --- шаблоны ответа по языкам (офлайн) ---
    fun template(lang: String): String {
        val def = when (lang) {
            "ru" -> "Здравствуйте! Сейчас мы закрыты. Пожалуйста, перезвоните завтра."
            "cs" -> "Dobrý den! Máme zavřeno. Zavolejte prosím zítra."
            else -> "Hello! We are currently closed. Please call back tomorrow."
        }
        return sp.getString(K_TPL_PREFIX + lang, def) ?: def
    }
    fun setTemplate(lang: String, text: String) =
        sp.edit().putString(K_TPL_PREFIX + lang, text).apply()

    var defaultLang: String
        get() = sp.getString(K_DEF_LANG, "en") ?: "en"
        set(v) = sp.edit().putString(K_DEF_LANG, v).apply()

    // --- Редактируемые промпты (LLM) ---
    var promptCall: String
        get() = sp.getString(K_PROMPT_CALL, DEF_PROMPT_CALL) ?: DEF_PROMPT_CALL
        set(v) = sp.edit().putString(K_PROMPT_CALL, v).apply()

    var promptSms: String
        get() = sp.getString(K_PROMPT_SMS, DEF_PROMPT_SMS) ?: DEF_PROMPT_SMS
        set(v) = sp.edit().putString(K_PROMPT_SMS, v).apply()

    /** Префикс в начале каждого исходящего сообщения (пометка, что это ИИ). */
    var aiPrefix: String
        get() = sp.getString(K_AI_PREFIX, DEF_AI_PREFIX) ?: DEF_AI_PREFIX
        set(v) = sp.edit().putString(K_AI_PREFIX, v).apply()

    // --- LLM ---
    var llmEnabled: Boolean
        get() = sp.getBoolean(K_LLM_ON, false)
        set(v) = sp.edit().putBoolean(K_LLM_ON, v).apply()

    /** "ollama" | "openai" | "claude" */
    var llmProvider: String
        get() = sp.getString(K_LLM_PROV, "ollama") ?: "ollama"
        set(v) = sp.edit().putString(K_LLM_PROV, v).apply()

    var llmBaseUrl: String
        get() = sp.getString(K_LLM_URL, "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434"
        set(v) = sp.edit().putString(K_LLM_URL, v).apply()

    var llmApiKey: String
        get() = sp.getString(K_LLM_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_LLM_KEY, v).apply()

    var llmModel: String
        get() = sp.getString(K_LLM_MODEL, "") ?: ""
        set(v) = sp.edit().putString(K_LLM_MODEL, v).apply()

    companion object {
        private const val K_ENABLED = "enabled"
        private const val K_TRIG_DND = "trig_dnd"
        private const val K_TRIG_SCHED = "trig_sched"
        private const val K_SCHED_START = "sched_start"
        private const val K_SCHED_END = "sched_end"
        private const val K_PREFIXES = "prefixes"
        private const val K_EXCLUDED = "excluded"
        private const val K_EXCL_STARRED = "excl_starred"
        private const val K_EXCL_CONTACTS = "excl_contacts"
        private const val K_RESPECT_DND = "respect_dnd"
        private const val K_RESP_CALLS = "resp_calls"
        private const val K_RESP_SMS = "resp_sms"
        private const val K_MAX_REPLIES = "max_replies"
        private const val K_TIMEOUT = "timeout_h"
        private const val K_MAX_SEG = "max_seg"
        private const val K_TPL_PREFIX = "tpl_"
        private const val K_DEF_LANG = "def_lang"
        private const val K_LLM_ON = "llm_on"
        private const val K_LLM_PROV = "llm_prov"
        private const val K_LLM_URL = "llm_url"
        private const val K_LLM_KEY = "llm_key"
        private const val K_LLM_MODEL = "llm_model"
        private const val K_PROMPT_CALL = "prompt_call"
        private const val K_PROMPT_SMS = "prompt_sms"
        private const val K_AI_PREFIX = "ai_prefix"

        const val DEF_AI_PREFIX = "Ответ от AI:"
        const val DEF_PROMPT_CALL =
            "Ты — вежливый автоответчик компании, которая сейчас закрыта. " +
            "Клиент звонил, но мы не можем ответить сейчас. Кратко, в рамках лимита символов, " +
            "сообщи, что ответить сейчас не можем, попроси написать SMS, и что ответим при первой возможности."
        const val DEF_PROMPT_SMS =
            "Ты — вежливый автоответчик компании, которая сейчас закрыта. " +
            "Ответь кратко и по делу в рамках лимита символов, учитывая содержание сообщения клиента. " +
            "Поблагодари и сообщи, что ответим как можно скорее. " +
            "Новому контакту: «спасибо за сообщение, ответим вам как можно быстрее». " +
            "Тому, кто уже обращался: «спасибо, ответим как можно быстрее»."
    }
}
