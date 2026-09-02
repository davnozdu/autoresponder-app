package com.davnozdu.autoresponder.store

import android.content.Context
import java.io.File

/**
 * Прайс-лист как ФАКТЫ, а не как проза.
 *
 * Тот же принцип, что и в разговоре о статусе заказа: цифры собирает код, модель
 * только оформляет фразу. Придуманная моделью «замена экрана 2500 Kč» — это спор
 * с клиентом на пороге и работа себе в убыток; ошибиться здесь дороже, чем промолчать.
 *
 * Источник (по приоритету): загруженная копия filesDir/prices.csv → /sdcard/AutoResponder/prices.csv.
 * Формат строки: `устройство;услуга;цена;срок`. `#` и пустые строки игнорируются,
 * первая строка может быть заголовком. Разделитель — `;` или, если его нет, `,`.
 */
object Prices {

    const val PUBLIC_PATH = "/sdcard/AutoResponder/prices.csv"

    data class Row(val device: String, val service: String, val price: String, val term: String) {
        fun line(): String = listOf(device, service, price, term)
            .filter { it.isNotBlank() }.joinToString(" — ")
    }

    @Volatile private var cacheStamp: String = ""
    @Volatile private var cacheRows: List<Row> = emptyList()

    private fun appCopy(context: Context) = File(context.filesDir, "prices.csv")

    private fun pick(context: Context): File? {
        val app = appCopy(context)
        if (app.exists() && app.length() > 0) return app
        return try {
            val pub = File(PUBLIC_PATH)
            if (pub.exists() && pub.length() > 0) pub else null
        } catch (_: Exception) { null }
    }

    fun source(context: Context): String? = pick(context)?.path

    fun rows(context: Context): List<Row> {
        val f = pick(context) ?: return emptyList()
        return try {
            val stamp = "${f.path}:${f.lastModified()}:${f.length()}"
            if (stamp != cacheStamp) {
                cacheRows = parse(f.readText())
                cacheStamp = stamp
            }
            cacheRows
        } catch (_: Exception) { emptyList() }
    }

    fun parse(text: String): List<Row> {
        val out = ArrayList<Row>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = if (line.contains(';')) ';' else ','
            val p = line.split(sep).map { it.trim() }
            if (p.size < 3) continue
            // Заголовок таблицы: цена не число — значит это подписи колонок.
            if (out.isEmpty() && p[2].none { it.isDigit() }) continue
            out.add(Row(p[0], p[1], p[2], p.getOrElse(3) { "" }))
        }
        return out
    }

    private val ASK = listOf(
        "сколько стоит", "сколько будет", "цена", "цены", "стоимость", "почём", "почем", "прайс",
        "kolik stoji", "kolik stojí", "cena", "ceny", "cenik", "ceník",
        "how much", "price", "cost")

    /** Спрашивают ли про деньги. */
    fun looksLikePriceQuestion(text: String?): Boolean {
        val t = text?.lowercase()?.replace('ё', 'е') ?: return false
        return ASK.any { it in t }
    }

    /** Строки прайса, похожие на вопрос: слова вопроса против устройства и услуги. */
    fun match(rows: List<Row>, question: String, limit: Int = 8): List<Row> {
        val words = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 }
        if (words.isEmpty()) return emptyList()
        return rows.map { r ->
            val hay = (r.device + " " + r.service).lowercase()
            r to words.count { w -> hay.contains(w.take(5)) }
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    /**
     * Блок для промпта. Пустая строка — вопрос не про деньги или прайса нет.
     *
     * Когда прайс есть, а подходящей строки в нём нет, блок всё равно возвращается —
     * с запретом называть цену. Это и есть главная защита: без него модель охотно
     * придумает «примерно 2000 Kč».
     */
    fun promptBlock(context: Context, question: String?): String {
        if (!looksLikePriceQuestion(question)) return ""
        val all = rows(context)
        if (all.isEmpty()) return ""
        val hit = match(all, question ?: "")
        val body = if (hit.isEmpty()) "(ничего подходящего в прайсе не нашлось)"
                   else hit.joinToString("\n") { "- " + it.line() }
        return """

            PRICE LIST (authoritative, do not invent):
            $body
            Rules about money: name a price ONLY if it is in the list above, and quote it exactly.
            If it is not there, say the exact price will be confirmed by a technician after diagnostics.
            Never estimate, never give a range of your own, never promise a discount.
        """.trimIndent()
    }

    fun saveAppCopy(context: Context, text: String?) {
        val f = appCopy(context)
        try {
            if (text.isNullOrBlank()) f.delete() else f.writeText(text)
            cacheStamp = ""
        } catch (_: Exception) {}
    }
}
