package com.davnozdu.autoresponder.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

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

    // --- приложения-мессенджеры для мониторинга (пакеты) ---
    var monitoredApps: Set<String>
        get() = sp.getStringSet(K_MON_APPS, DEFAULT_APPS)!!.toSet()
        set(v) = sp.edit().putStringSet(K_MON_APPS, v).apply()

    // --- выбор SIM для отправки: -1 системная, 0=SIM1, 1=SIM2 (по умолчанию SIM2) ---
    var smsSlot: Int
        get() = sp.getInt(K_SMS_SLOT, 1)
        set(v) = sp.edit().putInt(K_SMS_SLOT, v).apply()

    // --- задержка перед авто-ответом (мс), для звонков и SMS ---
    var replyDelayMs: Long
        get() = sp.getLong(K_REPLY_DELAY, 1500L)
        set(v) = sp.edit().putLong(K_REPLY_DELAY, v).apply()

    // --- максимум SMS-сегментов в одном ответе ---
    var maxSegments: Int
        get() = sp.getInt(K_MAX_SEG, 6)
        set(v) = sp.edit().putInt(K_MAX_SEG, v).apply()

    // --- шаблоны ответа по языкам (офлайн) ---
    fun template(lang: String): String {
        val def = when (lang) {
            "ru" -> "Здравствуйте! Сейчас нерабочее время. Ответим в ближайшее рабочее время."
            "cs" -> "Dobrý den! Momentálně máme zavřeno. Odpovíme v nejbližší pracovní době."
            else -> "Hello! We are currently closed. We will reply during our next working hours."
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

    /** Факты о компании (база знаний для LLM): часы, ссылки, email, политика ремонта. */
    var businessInfo: String
        get() = sp.getString(K_BIZ, DEF_BUSINESS_INFO) ?: DEF_BUSINESS_INFO
        set(v) = sp.edit().putString(K_BIZ, v).apply()

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
        get() = sp.getString(K_LLM_URL, "https://ollama.com") ?: "https://ollama.com"
        set(v) = sp.edit().putString(K_LLM_URL, v).apply()

    var llmApiKey: String
        get() = sp.getString(K_LLM_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_LLM_KEY, v).apply()

    var llmModel: String
        get() = sp.getString(K_LLM_MODEL, "") ?: ""
        set(v) = sp.edit().putString(K_LLM_MODEL, v).apply()

    // --- Импорт/экспорт всех настроек (с сохранением типов) ---
    fun exportJson(): String {
        val root = JSONObject()
        for ((k, v) in sp.all) {
            val o = JSONObject()
            when (v) {
                is Boolean -> { o.put("t", "b"); o.put("v", v) }
                is Int -> { o.put("t", "i"); o.put("v", v) }
                is Long -> { o.put("t", "l"); o.put("v", v) }
                is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                is String -> { o.put("t", "s"); o.put("v", v) }
                else -> continue
            }
            root.put(k, o)
        }
        return root.toString(2)
    }

    /** Возвращает true при успехе. Полностью заменяет текущие настройки. */
    fun importJson(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val e = sp.edit().clear()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                }
            }
            e.apply()
            true
        } catch (ex: Exception) {
            false
        }
    }

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
        private const val K_REPLY_DELAY = "reply_delay_ms"
        private const val K_SMS_SLOT = "sms_slot"
        private const val K_MON_APPS = "monitored_apps"
        val DEFAULT_APPS = setOf(
            "com.google.android.apps.messaging", "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger")
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
        private const val K_BIZ = "business_info"

        const val DEF_AI_PREFIX = "Ответ от AI:"
        const val DEF_PROMPT_CALL =
            "Ты — вежливый автоответчик компании (сейчас нерабочее время). " +
            "Клиент звонил, но мы не можем ответить сейчас. Кратко, в рамках лимита символов, " +
            "сообщи, что сейчас ответить не можем, попроси написать SMS, и что ответим или перезвоним в ближайшее рабочее время."

        const val DEF_PROMPT_SMS =
            "Ты — автоответчик-секретарь компании (сейчас нерабочее время). " +
            "Отвечай кратко, вежливо и СТРОГО в рамках лимита символов, на языке клиента, используя факты о компании. " +
            "Если клиент ПРЯМО спрашивает про часы работы / когда открыто / когда можно подойти — сообщи рабочее время и дай ссылку на запись. " +
            "Если клиент ПРЯМО спрашивает про ремонт — скажи, что возможность ремонта зависит от поломки, и попроси прислать данные о проблеме и устройстве на email. " +
            "Если сообщение непонятное, очень короткое или без явного вопроса — НЕ придумывай тему (не предлагай ремонт и т.п.), просто вежливо сообщи, что сейчас нерабочее время. " +
            "НЕ обещай ответить «как можно скорее» — пиши, что ответим в ближайшее рабочее время / по мере возможности. " +
            "Новому контакту: «Спасибо за сообщение, ответим вам в ближайшее рабочее время». " +
            "Повторному: «Спасибо, ответим в ближайшее рабочее время»."

        const val DEF_BUSINESS_INFO =
            "Рабочее время: Пн-Пт 08:00-17:00 (Сб-Вс выходной). " +
            "Запись: https://cal.com/bigtweak/bookcz . " +
            "Все резервации: https://cal.com/bigtweak . " +
            "Ремонт: зависит от поломки — данные о проблеме и устройстве присылать на info@bigtweak.cz"
    }
}
