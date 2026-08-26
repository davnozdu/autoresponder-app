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

    // --- уведомления ---
    var notificationsEnabled: Boolean
        get() = sp.getBoolean(K_NOTIF_ON, true)
        set(v) = sp.edit().putBoolean(K_NOTIF_ON, v).apply()

    // пауза авто-ответа: 0=нет, 1=до следующего DND, 2=до перезагрузки
    var pauseMode: Int
        get() = sp.getInt(K_PAUSE_MODE, 0)
        set(v) = sp.edit().putInt(K_PAUSE_MODE, v).apply()
    var pauseBootMarker: Long
        get() = sp.getLong(K_PAUSE_BOOT, 0L)
        set(v) = sp.edit().putLong(K_PAUSE_BOOT, v).apply()
    var dndWasOn: Boolean
        get() = sp.getBoolean(K_DND_WAS_ON, false)
        set(v) = sp.edit().putBoolean(K_DND_WAS_ON, v).apply()
    // уведомления ЧС: 0=выкл, 1=через N мин, 2=сводка за день
    var blNotifMode: Int
        get() = sp.getInt(K_BLN_MODE, 1)
        set(v) = sp.edit().putInt(K_BLN_MODE, v).apply()
    var blNotifDelayMin: Int
        get() = sp.getInt(K_BLN_DELAY, 60)
        set(v) = sp.edit().putInt(K_BLN_DELAY, v).apply()
    var blDailyTimeMin: Int
        get() = sp.getInt(K_BLN_DAILY, 9 * 60)
        set(v) = sp.edit().putInt(K_BLN_DAILY, v).apply()
    var blAlarmSet: Boolean
        get() = sp.getBoolean(K_BLN_ALARM, false)
        set(v) = sp.edit().putBoolean(K_BLN_ALARM, v).apply()
    var lastDndOnTime: Long
        get() = sp.getLong(K_DND_ON_TS, 0L)
        set(v) = sp.edit().putLong(K_DND_ON_TS, v).apply()

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

    // режим расписания: 0 = закрытое окно (start..end), 1 = рабочие часы/дни
    var scheduleMode: Int
        get() = sp.getInt(K_SCHED_MODE, 0)
        set(v) = sp.edit().putInt(K_SCHED_MODE, v).apply()
    var workStartMin: Int
        get() = sp.getInt(K_WORK_START, 8 * 60)
        set(v) = sp.edit().putInt(K_WORK_START, v).apply()
    var workEndMin: Int
        get() = sp.getInt(K_WORK_END, 17 * 60)
        set(v) = sp.edit().putInt(K_WORK_END, v).apply()
    // битовая маска рабочих дней (Calendar.DAY_OF_WEEK 1..7); деф Пн-Пт = 124
    var workDaysMask: Int
        get() = sp.getInt(K_WORK_DAYS, 124)
        set(v) = sp.edit().putInt(K_WORK_DAYS, v).apply()

    /** начало окна «закрыто», минуты от полуночи (напр. 21:00 = 1260) */
    var scheduleStartMin: Int
        get() = sp.getInt(K_SCHED_START, 21 * 60)
        set(v) = sp.edit().putInt(K_SCHED_START, v).apply()

    /** конец окна «закрыто», минуты от полуночи (напр. 09:00 = 540) */
    var scheduleEndMin: Int
        get() = sp.getInt(K_SCHED_END, 9 * 60)
        set(v) = sp.edit().putInt(K_SCHED_END, v).apply()

    // --- маска стран, раздельно по SIM ---
    // Номер обслуживается, если попал в список ЛЮБОЙ из карт; с какой карты отвечаем —
    // определяет то, в чей список он попал (см. slotForNumber).
    private fun prefixList(key: String, def: String): List<String> =
        (sp.getString(key, def) ?: def).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // Миграция со старой ОБЩЕЙ маски: она достаётся той карте, которая уже была выбрана для
    // отправки. Иначе у всех, у кого стояла SIM2, ответы после обновления ушли бы с SIM1.
    private fun legacyMask() = sp.getString(K_PREFIXES, "+420") ?: "+420"

    /** Используется ли SIM1 для автоответа (карту можно выключить целиком). */
    var sim1Enabled: Boolean
        get() = sp.getBoolean(K_SIM1_ON, smsSlot == 0 || prefixList(K_PREFIXES_S1, "").isNotEmpty())
        set(v) = sp.edit().putBoolean(K_SIM1_ON, v).apply()

    /** Используется ли SIM2 для автоответа. */
    var sim2Enabled: Boolean
        get() = sp.getBoolean(K_SIM2_ON, smsSlot == 1 || prefixList(K_PREFIXES_S2, "").isNotEmpty())
        set(v) = sp.edit().putBoolean(K_SIM2_ON, v).apply()

    /** Префиксы, которые обслуживает SIM1 (пусто, если карта выключена). */
    var prefixesSim1: List<String>
        get() = if (!sim1Enabled) emptyList()
                else prefixList(K_PREFIXES_S1, if (smsSlot == 0) legacyMask() else "")
        set(v) = sp.edit().putString(K_PREFIXES_S1, v.joinToString(",")).apply()

    /** Префиксы, которые обслуживает SIM2 (пусто, если карта выключена). */
    var prefixesSim2: List<String>
        get() = if (!sim2Enabled) emptyList()
                else prefixList(K_PREFIXES_S2, if (smsSlot == 1) legacyMask() else "")
        set(v) = sp.edit().putString(K_PREFIXES_S2, v.joinToString(",")).apply()

    /** Сырые списки для UI: показываем введённое даже у выключенной карты. */
    fun prefixesRaw(slot: Int): List<String> =
        if (slot == 0) prefixList(K_PREFIXES_S1, if (smsSlot == 0) legacyMask() else "")
        else prefixList(K_PREFIXES_S2, if (smsSlot == 1) legacyMask() else "")

    /** Префиксы, попавшие в оба списка сразу — конфликт правил (для предупреждения в UI). */
    fun conflictingPrefixes(): List<String> {
        fun norm(p: String) = (if (p.startsWith("+")) p else "+$p").trim()
        val a = prefixesRaw(0).map(::norm).toSet()
        val b = prefixesRaw(1).map(::norm).toSet()
        return (a intersect b).sorted()
    }

    /** Общая маска: объединение правил ВКЛЮЧЁННЫХ карт (кому вообще отвечаем). */
    val allowedPrefixes: List<String>
        get() = (prefixesSim1 + prefixesSim2).distinct()

    /** SIM по умолчанию с оглядкой на выключенные карты. */
    private val effectiveDefaultSlot: Int
        get() = when {
            smsSlot == 0 && sim1Enabled -> 0
            smsSlot == 1 && sim2Enabled -> 1
            sim2Enabled -> 1
            sim1Enabled -> 0
            else -> smsSlot          // обе выключены — отвечать всё равно некому
        }

    /**
     * Слот SIM для ответа этому номеру: 0 = SIM1, 1 = SIM2.
     *
     * Выигрывает САМОЕ ДЛИННОЕ подходящее правило: если у SIM1 стоит «+4», а у SIM2 «+420»,
     * номер +420… уйдёт с SIM2 как с более точного совпадения. При полностью одинаковом
     * префиксе в обоих списках спор решается в пользу SIM по умолчанию — так поведение
     * предсказуемо, а не зависит от порядка проверки.
     */
    fun slotForNumber(number: String?): Int {
        val n = com.davnozdu.autoresponder.rules.PhoneMask.normalize(number) ?: return effectiveDefaultSlot
        fun bestMatch(list: List<String>): Int = list.mapNotNull { p ->
            val pp = if (p.startsWith("+")) p else "+$p"
            if (n.startsWith(pp)) pp.length else null
        }.maxOrNull() ?: -1

        val m1 = bestMatch(prefixesSim1)
        val m2 = bestMatch(prefixesSim2)
        return when {
            m1 < 0 && m2 < 0 -> effectiveDefaultSlot
            m1 == m2 -> effectiveDefaultSlot      // одинаковые правила — решает карта по умолчанию
            m2 > m1 -> 1
            else -> 0
        }
    }

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

    // --- Избранные для мессенджеров: имена/@-юзернеймы (Telegram отдаёт только имена/@username) ---
    var excludedNames: List<String>
        get() = sp.getString(K_EXCL_NAMES, "")!!
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(K_EXCL_NAMES, v.joinToString("\n")).apply()

    fun addExcludedName(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val cur = excludedNames.toMutableList()
        if (cur.none { it.equals(n, true) }) { cur.add(n); excludedNames = cur }
    }
    fun removeExcludedName(name: String) {
        excludedNames = excludedNames.filterNot { it.equals(name.trim(), true) }
    }
    /** Отправитель-мессенджер в списке избранных (сравнение без учёта регистра и префикса @). */
    fun isExcludedName(sender: String?): Boolean {
        val raw = sender?.trim()?.lowercase()?.removePrefix("@") ?: return false
        if (raw.isEmpty()) return false
        return excludedNames.any { e ->
            val ee = e.trim().lowercase().removePrefix("@")
            ee.isNotEmpty() && raw == ee
        }
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

    // --- анти-флуд: не более N обычных авто-ответов на номер, затем 1 предупреждение и таймаут ---
    var maxReplies: Int
        get() = sp.getInt(K_MAX_REPLIES, 6)
        set(v) = sp.edit().putInt(K_MAX_REPLIES, v).apply()

    /** таймаут после исчерпания лимита, часы (по умолчанию 1 ч = 60 мин) */
    var timeoutHours: Int
        get() = sp.getInt(K_TIMEOUT, 1)
        set(v) = sp.edit().putInt(K_TIMEOUT, v).apply()

    /** Отправлять ли одно системное предупреждение (№ maxReplies+1) перед уходом в тишину. */
    var warnEnabled: Boolean
        get() = sp.getBoolean(K_WARN_ON, true)
        set(v) = sp.edit().putBoolean(K_WARN_ON, v).apply()

    /** Промпт для генерации предупреждающего сообщения (LLM); {hours} — часы таймаута. */
    var promptWarn: String
        get() = sp.getString(K_PROMPT_WARN, DEF_PROMPT_WARN) ?: DEF_PROMPT_WARN
        set(v) = sp.edit().putString(K_PROMPT_WARN, v).apply()

    /** Сырой шаблон предупреждения (с плейсхолдером {hours}) — для редактирования в UI. */
    fun warnTemplateRaw(lang: String): String {
        val def = defWarnTemplate(lang)
        return sp.getString(K_TPL_WARN_PREFIX + lang, def) ?: def
    }

    private fun defWarnTemplate(lang: String): String = when (lang) {
        "ru" -> "Это автоматический ответ (AI). Ваши сообщения увидит живой сотрудник в ближайшее рабочее время или они будут обработаны примерно через {hours} ч. Можете продолжать писать — мы всё получим."
        "cs" -> "Toto je automatická odpověď (AI). Vaše zprávy uvidí náš pracovník v nejbližší pracovní době, případně budou zpracovány přibližně za {hours} h. Klidně pište dál — vše dostaneme."
        else -> "This is an automated (AI) reply. A human will see your messages during our next working hours, or they will be handled in about {hours} h. Feel free to keep writing — we'll receive everything."
    }

    /** Офлайн-шаблон предупреждения по языку; {hours} заменяется на часы таймаута. */
    fun warnTemplate(lang: String, hours: Int): String =
        warnTemplateRaw(lang).replace("{hours}", hours.toString())
    fun setWarnTemplate(lang: String, text: String) =
        sp.edit().putString(K_TPL_WARN_PREFIX + lang, text).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(K_UPD_CHECK, 0L)
        set(v) = sp.edit().putLong(K_UPD_CHECK, v).apply()

    // --- Ежедневный бэкап БД истории ---
    var backupEnabled: Boolean
        get() = sp.getBoolean(K_BK_ON, true)
        set(v) = sp.edit().putBoolean(K_BK_ON, v).apply()
    /** сколько копий хранить (ротация) */
    var backupKeep: Int
        get() = sp.getInt(K_BK_KEEP, 10)
        set(v) = sp.edit().putInt(K_BK_KEEP, v).apply()
    /** час суток для бэкапа (0-23) */
    var backupHour: Int
        get() = sp.getInt(K_BK_HOUR, 3)
        set(v) = sp.edit().putInt(K_BK_HOUR, v).apply()
    var lastBackup: Long
        get() = sp.getLong(K_BK_LAST, 0L)
        set(v) = sp.edit().putLong(K_BK_LAST, v).apply()
    /** Включать API-ключи LLM в экспорт настроек (буфер/файл). По умолчанию выкл. */
    var exportSecrets: Boolean
        get() = sp.getBoolean(K_EXP_SECRETS, false)
        set(v) = sp.edit().putBoolean(K_EXP_SECRETS, v).apply()

    /** Учитывать список праздников (когда офис закрыт) в ответах LLM. По умолчанию выкл. */
    var holidaysEnabled: Boolean
        get() = sp.getBoolean(K_HOL_ON, false)
        set(v) = sp.edit().putBoolean(K_HOL_ON, v).apply()

    // --- макс. возраст уведомления для ответа (мин) — защита от старых/восстановленных ---
    var notifMaxAgeMin: Int
        get() = sp.getInt(K_NOTIF_AGE, 5)
        set(v) = sp.edit().putInt(K_NOTIF_AGE, v).apply()

    // --- приложения-мессенджеры для мониторинга (пакеты) ---
    var monitoredApps: Set<String>
        get() = sp.getStringSet(K_MON_APPS, DEFAULT_APPS)!!.toSet()
        set(v) = sp.edit().putStringSet(K_MON_APPS, v).apply()

    // --- SIM по умолчанию: 0=SIM1, 1=SIM2. «Системная» убрана: она отдавала отправку
    // системному выбору по умолчанию, и ответ уходил не с той карты. ---
    var smsSlot: Int
        get() = sp.getInt(K_SMS_SLOT, 1).coerceIn(0, 1)
        set(v) = sp.edit().putInt(K_SMS_SLOT, v.coerceIn(0, 1)).apply()

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

    // --- Резервная LLM (если основная недоступна) ---
    var llm2Enabled: Boolean
        get() = sp.getBoolean(K_LLM2_ON, false)
        set(v) = sp.edit().putBoolean(K_LLM2_ON, v).apply()
    var llm2Provider: String
        get() = sp.getString(K_LLM2_PROV, "gemini") ?: "gemini"
        set(v) = sp.edit().putString(K_LLM2_PROV, v).apply()
    var llm2BaseUrl: String
        get() = sp.getString(K_LLM2_URL, "") ?: ""
        set(v) = sp.edit().putString(K_LLM2_URL, v).apply()
    var llm2ApiKey: String
        get() = sp.getString(K_LLM2_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_LLM2_KEY, v).apply()
    var llm2Model: String
        get() = sp.getString(K_LLM2_MODEL, "") ?: ""
        set(v) = sp.edit().putString(K_LLM2_MODEL, v).apply()

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

    // --- дневной лимит обращений к LLM (0 = без лимита) ---
    var llmDailyCap: Int
        get() = sp.getInt(K_LLM_CAP, 0)
        set(v) = sp.edit().putInt(K_LLM_CAP, v).apply()
    var llmDay: Int
        get() = sp.getInt(K_LLM_DAY, 0)
        set(v) = sp.edit().putInt(K_LLM_DAY, v).apply()
    var llmCount: Int
        get() = sp.getInt(K_LLM_COUNT, 0)
        set(v) = sp.edit().putInt(K_LLM_COUNT, v).apply()

    // --- LLM ---
    var llmEnabled: Boolean
        get() = sp.getBoolean(K_LLM_ON, false)
        set(v) = sp.edit().putBoolean(K_LLM_ON, v).apply()

    /** Режим размышления (reasoning). false — прямой краткий ответ (подходит всем моделям);
     *  true — модель думает (большой бюджет токенов), ответ всё равно обрезается под лимит SMS. */
    var llmThink: Boolean
        get() = sp.getBoolean(K_LLM_THINK, false)
        set(v) = sp.edit().putBoolean(K_LLM_THINK, v).apply()

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
    /**
     * @param withKeys включать ли API-ключи LLM. По умолчанию НЕТ: выгрузка уходит в буфер обмена
     *   и в файл на общем хранилище, где ключи доступны другим приложениям.
     */
    fun exportJson(withKeys: Boolean = false): String {
        val root = JSONObject()
        for ((k, v) in sp.all) {
            if (!withKeys && k in SECRET_KEYS) continue
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

    /**
     * Возвращает true при успехе. Полностью заменяет текущие настройки.
     * Секреты, которых нет в файле (экспорт без ключей), СОХРАНЯЮТСЯ — иначе импорт
     * настроек молча стирал бы уже введённые API-ключи.
     */
    fun importJson(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val keptSecrets = SECRET_KEYS
                .filter { !root.has(it) }
                .mapNotNull { k -> sp.getString(k, null)?.let { k to it } }
            val e = sp.edit().clear()
            for ((k, v) in keptSecrets) e.putString(k, v)
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
        private const val K_NOTIF_ON = "notif_on"
        private const val K_PAUSE_MODE = "pause_mode"
        private const val K_PAUSE_BOOT = "pause_boot"
        private const val K_DND_ON_TS = "dnd_on_ts"
        private const val K_DND_WAS_ON = "dnd_was_on"
        private const val K_BLN_MODE = "bln_mode"
        private const val K_BLN_DELAY = "bln_delay"
        private const val K_BLN_DAILY = "bln_daily"
        private const val K_BLN_ALARM = "bln_alarm"
        private const val K_TRIG_DND = "trig_dnd"
        private const val K_TRIG_SCHED = "trig_sched"
        private const val K_SCHED_MODE = "sched_mode"
        private const val K_WORK_START = "work_start"
        private const val K_WORK_END = "work_end"
        private const val K_WORK_DAYS = "work_days"
        private const val K_SCHED_START = "sched_start"
        private const val K_SCHED_END = "sched_end"
        private const val K_PREFIXES = "prefixes"        // старая общая маска (миграция)
        private const val K_PREFIXES_S1 = "prefixes_sim1"
        private const val K_PREFIXES_S2 = "prefixes_sim2"
        private const val K_SIM1_ON = "sim1_on"
        private const val K_SIM2_ON = "sim2_on"
        private const val K_EXCLUDED = "excluded"
        private const val K_EXCL_NAMES = "excluded_names"
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
        private const val K_NOTIF_AGE = "notif_age_min"
        private const val K_UPD_CHECK = "last_upd_check"
        private const val K_BK_ON = "backup_on"
        private const val K_BK_KEEP = "backup_keep"
        private const val K_BK_HOUR = "backup_hour"
        private const val K_BK_LAST = "backup_last"
        private const val K_HOL_ON = "holidays_on"
        private const val K_EXP_SECRETS = "export_secrets"
        val DEFAULT_APPS = setOf(
            "com.google.android.apps.messaging", "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger")
        private const val K_TPL_PREFIX = "tpl_"
        private const val K_DEF_LANG = "def_lang"
        private const val K_LLM_CAP = "llm_cap"
        private const val K_LLM_DAY = "llm_day"
        private const val K_LLM_COUNT = "llm_count"
        private const val K_LLM_ON = "llm_on"
        private const val K_LLM_THINK = "llm_think"
        private const val K_LLM_PROV = "llm_prov"
        private const val K_LLM_URL = "llm_url"
        private const val K_LLM_KEY = "llm_key"
        private const val K_LLM_MODEL = "llm_model"
        private const val K_LLM2_ON = "llm2_on"
        private const val K_LLM2_PROV = "llm2_prov"
        private const val K_LLM2_URL = "llm2_url"
        private const val K_LLM2_KEY = "llm2_key"
        private const val K_LLM2_MODEL = "llm2_model"
        private const val K_PROMPT_CALL = "prompt_call"
        private const val K_PROMPT_SMS = "prompt_sms"
        private const val K_PROMPT_WARN = "prompt_warn"
        private const val K_WARN_ON = "warn_on"
        private const val K_TPL_WARN_PREFIX = "tplwarn_"
        private const val K_AI_PREFIX = "ai_prefix"
        private const val K_BIZ = "business_info"

        /** Ключи, которые не попадают в экспорт настроек без явного согласия. */
        private val SECRET_KEYS = setOf(K_LLM_KEY, K_LLM2_KEY)

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

        const val DEF_PROMPT_WARN =
            "Ты — автоответчик компании. Клиент исчерпал лимит автоматических ответов. " +
            "Напиши ОДНО короткое вежливое сообщение на языке клиента: что дальше отвечает автоответчик (AI), " +
            "а живой сотрудник увидит переписку в ближайшее рабочее время либо сообщения будут обработаны примерно через {hours} ч. " +
            "Добавь, что клиент может продолжать писать — всё будет получено. Без подписи и эмодзи, строго в рамках лимита символов."

        const val DEF_BUSINESS_INFO =
            "Рабочее время: Пн-Пт 08:00-17:00 (Сб-Вс выходной). " +
            "Запись: https://cal.com/bigtweak/bookcz . " +
            "Все резервации: https://cal.com/bigtweak . " +
            "Ремонт: зависит от поломки — данные о проблеме и устройстве присылать на info@bigtweak.cz"
    }
}
