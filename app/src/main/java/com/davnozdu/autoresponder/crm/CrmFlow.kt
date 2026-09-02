package com.davnozdu.autoresponder.crm

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings

/**
 * Разговор о статусе заказа.
 *
 * Факты собираются кодом, а не моделью: придуманное «будет готов завтра» породит не
 * меньше звонков, а больше — то есть ровно обратное тому, ради чего всё затевалось.
 * LLM здесь не участвует; ей достаётся [promptBlock] на случай, когда клиент спросил
 * не про статус, но заказ у него есть — тогда пусть говорит своими словами, зная факты.
 *
 * Порядок разговора:
 *   вопрос → статус (или «про какой заказ?») → клиент пишет ДА → вопрос уходит мастеру.
 */
object CrmFlow {

    private const val CACHE_MS = 5 * 60_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CrmLookup>>()

    /* ---------------- Определение вопроса ---------------- */

    // Слова о предмете разговора и слова о его состоянии. Требуем и то, и другое:
    // одно «когда» есть и в «когда вы работаете?», на который отвечать статусом нельзя.
    private val subject = listOf(
        "заказ", "ремонт", "устройств", "телефон", "ноутбук", "планшет", "часы", "приняли",
        "zakázk", "zakazk", "oprav", "telefon", "notebook", "zařízen", "zarizen",
        "order", "repair", "device", "laptop", "phone")
    private val state = listOf(
        "готов", "статус", "когда", "что с", "как там", "что там", "срок", "долго", "ещё не",
        "hotov", "stav", "kdy", "jak dlouho", "termín", "termin",
        "ready", "status", "when", "how long", "eta", "done")

    private fun norm(t: String) = t.lowercase().replace('ё', 'е')

    fun looksLikeStatusQuestion(text: String): Boolean {
        val t = norm(text)
        if (t.length > 400) return false          // длинное письмо — это не «ну что там?»
        val hasSubject = subject.any { it in t }
        val hasState = state.any { it in t }
        return hasSubject && hasState
    }

    private val yesWords = listOf(
        "да", "ага", "давай", "давайте", "конечно",
        "ano", "jo", "chci",
        "yes", "yep", "okay", "ok", "sure", "+", "👍", "✅")

    /**
     * Согласие позвать мастера и, если клиент его дописал, сам вопрос.
     *
     * Клиента просят написать «ДА и следом ваш вопрос», поэтому длина сообщения больше
     * ничего не решает — важно, что оно начинается со слова согласия. Остаток строки
     * и уходит мастеру: свежий вопрос полезнее того, с которого начался разговор.
     *
     * @return null, если согласия нет; иначе вопрос (может быть пустым).
     */
    fun yesWithQuestion(text: String): String? {
        val t = norm(text).trimStart()
        for (w in yesWords) {
            if (!t.startsWith(w)) continue
            val rest = t.substring(w.length)
            // «да» — согласие, «даже», «давление» — нет: за словом должен идти
            // разделитель или конец строки.
            if (rest.isNotEmpty() && (rest[0].isLetter() || rest[0].isDigit())) continue
            return rest.trimStart(' ', ',', '.', '!', ':', ';', '-', '—', '\n').trim()
        }
        return null
    }

    fun isYes(text: String): Boolean = yesWithQuestion(text) != null

    /* ---------------- Сопоставление заказа ---------------- */

    /**
     * Какой из заказов имеет в виду клиент.
     *
     * Смотрим тремя способами: цифры («42», «0042», «ZK-2026-0042»), слово из названия
     * устройства («ноутбук») и порядковый номер в списке. Совпал ровно один — он и есть;
     * совпало несколько — не угадываем.
     */
    fun match(text: String, records: List<CrmRecord>): CrmRecord? {
        if (records.isEmpty()) return null
        if (records.size == 1 && numbersIn(text).isEmpty()) return null
        val t = norm(text)
        val hits = LinkedHashSet<CrmRecord>()

        for (d in numbersIn(text)) {
            for (r in records) {
                val rd = r.number.filter { it.isDigit() }
                if (rd.isNotEmpty() && d.length >= 2 && rd.endsWith(d)) hits.add(r)
            }
        }
        if (hits.size == 1) return hits.first()

        for (r in records) {
            val words = norm(r.device).split(' ', '-', ',').filter { it.length >= 4 }
            if (words.any { it in t }) hits.add(r)
        }
        if (hits.size == 1) return hits.first()

        // Порядковый номер — только если в тексте больше ничего похожего на выбор.
        if (hits.isEmpty()) {
            val ord = when {
                t.startsWith("перв") || t.trim() == "1" -> 0
                t.startsWith("втор") || t.trim() == "2" -> 1
                t.startsWith("трет") || t.trim() == "3" -> 2
                else -> -1
            }
            if (ord in records.indices) return records[ord]
        }
        return null
    }

    /** Цифровые куски длиннее одного знака: «ZK-2026-0042» → 2026, 0042. */
    private fun numbersIn(text: String): List<String> =
        Regex("\\d{2,}").findAll(text).map { it.value }.toList()

    /* ---------------- Данные из CRM ---------------- */

    /**
     * Что в работе у этого человека, или null — если спрашивать не нужно либо не вышло.
     * Кеш на пять минут: серия «ну что там?», «алё», «???» подряд не должна давать три запроса.
     */
    fun lookup(context: Context, phones: List<String>): CrmLookup? {
        val s = Settings(context)
        if (!s.crmReady) return null
        if (!CrmRoster.shouldAsk(context, phones)) return null

        for (p in phones) {
            val key = p.filter { it.isDigit() }.takeLast(9)
            if (key.length < 6) continue
            cache[key]?.let { (ts, v) ->
                if (System.currentTimeMillis() - ts < CACHE_MS) {
                    return if (v.found) v else null
                }
            }
            val res = CrmApi.lookup(context, p) ?: continue
            cache[key] = System.currentTimeMillis() to res
            if (res.found && res.records.isNotEmpty()) return res
        }
        return null
    }

    fun invalidate() = cache.clear()

    /** В CRM чешский обозначен как «cz», в приложении — «cs». */
    private fun crmLang(v: String) = if (v.equals("cz", true)) "cs" else v.lowercase()

    /* ---------------- Разговор ---------------- */

    /**
     * Готовый ответ или null, если случай не наш и отвечать надо как обычно.
     *
     * @param key  ключ человека (тот же, что у истории), под ним живёт состояние разговора
     */
    fun answer(context: Context, key: String, phones: List<String>, text: String,
               channel: String, lang: String): String? {
        val s = Settings(context)
        if (!s.crmReady) return null
        val log = EventLog(context)

        val st = CrmState.get(context, key)
        // Язык разговора выбирается один раз (карточка клиента важнее определения по
        // тексту) и держится до конца: иначе ответ «ДА» латиницей уводил бы переписку
        // на английский посреди чешского разговора.
        val talk = st?.lang?.ifBlank { null } ?: lang

        // 1. Мы спрашивали, про какой заказ речь.
        if (st != null && st.kind == CrmState.Kind.CHOICE) {
            val picked = match(text, st.records)
            if (picked != null) {
                CrmState.put(context, key, CrmState.Kind.ASK, listOf(picked), st.question, talk)
                log.add("CRM[$key] выбран заказ ${picked.number}")
                return CrmText.status(picked, talk)
            }
            if (st.retries < 1) {
                CrmState.put(context, key, CrmState.Kind.CHOICE, st.records, st.question, talk, st.retries + 1)
                return CrmText.choose(st.records, talk)
            }
            // Второй промах — не мучаем: отдаём всё разом и уходим из режима выбора.
            CrmState.clear(context, key)
            log.add("CRM[$key] выбор не удался, отдаю все статусы")
            return CrmText.all(st.records, talk)
        }

        // 2. Мы предложили позвать мастера.
        val yes = if (st != null && st.kind == CrmState.Kind.ASK) yesWithQuestion(text) else null
        if (yes != null) {
            val rec = st!!.records.firstOrNull()
            CrmState.clear(context, key)
            if (rec == null) return null
            if (!rec.canAsk) return CrmText.askClosed(talk)
            val phone = phones.firstOrNull { it.filter { c -> c.isDigit() }.length >= 6 } ?: return null
            // Клиент дописал вопрос после «ДА» — уходит он; иначе тот, с которого
            // начался разговор.
            val question = yes.ifBlank { st.question }
            val err = CrmApi.ask(context, phone, rec.entity, rec.id, question, channel)
            log.add("CRM[$key] вопрос мастеру по ${rec.number}: ${err ?: "принят"}")
            return if (err == null) CrmText.asked(talk) else CrmText.askFailed(talk)
        }

        // 3. Новый вопрос о статусе.
        val hasNumber = numbersIn(text).isNotEmpty()
        if (!looksLikeStatusQuestion(text) && !hasNumber) return null

        val res = lookup(context, phones) ?: return null
        if (!res.found || res.records.isEmpty()) return null
        // Язык карточки клиента важнее определения по тексту: мастерская проставила его
        // осознанно. Карточка молчит — берём язык сообщения, а если и он не определился,
        // LangDetect вернул язык по умолчанию (у мастерской чешский).
        val l = if (res.lang.isNotBlank()) crmLang(res.lang) else lang

        // Номер заказа прямо в сообщении — отвечаем сразу про него, ничего не переспрашивая.
        match(text, res.records)?.let { rec ->
            CrmState.put(context, key, CrmState.Kind.ASK, listOf(rec), text, l)
            log.add("CRM[$key] статус по номеру из сообщения: ${rec.number}")
            return CrmText.status(rec, l)
        }

        if (res.records.size == 1) {
            val rec = res.records.first()
            CrmState.put(context, key, CrmState.Kind.ASK, listOf(rec), text, l)
            log.add("CRM[$key] статус: ${rec.number} — ${rec.label}")
            return CrmText.status(rec, l)
        }

        CrmState.put(context, key, CrmState.Kind.CHOICE, res.records, text, l)
        log.add("CRM[$key] активных записей ${res.records.size}, спрашиваю про какую")
        return CrmText.choose(res.records, l)
    }

    /**
     * Факты для промпта — когда клиент известен и заказ у него есть, но спросил он не
     * про статус. Пусть модель говорит своими словами, но с опорой на действительность.
     */
    fun promptBlock(context: Context, phones: List<String>): String {
        val res = lookup(context, phones) ?: return ""
        if (!res.found || res.records.isEmpty()) return ""
        val lines = res.records.joinToString("\n") { r ->
            val last = if (r.lastLabel != null) ", last update: ${r.lastLabel}" else ""
            "- ${if (r.entity == "claim") "claim" else "repair order"} ${r.number}" +
            (if (r.device.isNotBlank()) " (${r.device})" else "") + ": ${r.label}$last"
        }
        return "\nCRM — this client has work in progress right now:\n$lines\n" +
               "Mention it only if it fits the question. Never invent numbers, dates or prices.\n"
    }
}
