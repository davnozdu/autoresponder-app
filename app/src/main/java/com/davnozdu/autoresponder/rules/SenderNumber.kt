package com.davnozdu.autoresponder.rules

import android.content.Context

/**
 * Номер собеседника для канала Messages (SMS/RCS).
 *
 * Раньше номер брался ровно из одного места — из строки отправителя уведомления. Для SMS
 * там действительно стоит номер, а вот RCS показывает профиль отправителя: имя и аватар,
 * которые человек задал сам. Клиент с номером +420608930525 писал «Добрый день, как там
 * компьютер?», в уведомлении значилось «~Irina D'Arcy», номер не извлекался — и робот
 * молча пропускал сообщение («контакт без номера»). Так же вёл себя и любой сохранённый
 * контакт: в книге есть имя, значит в уведомлении имя, значит ответа нет.
 *
 * Номер без имени тоже нужен не для красоты: по нему работают маска стран, «Избранные»,
 * чёрный список, CRM и запасная отправка SMS. Поэтому спрашиваем по очереди все источники,
 * от самого дешёвого к самому дорогому, и сообщаем, какой сработал — иначе разбирать
 * «почему не ответили» снова будет не по чему.
 */
object SenderNumber {

    /** @param source откуда взят номер — уходит в журнал. */
    data class Found(val number: String, val source: String)

    /** Только цифры, минимум восемь: «tel:CeskaPosta» и «tel:4321» собеседниками не являются. */
    private fun asNumber(raw: String?): String? {
        val c = PhoneMask.canonical(raw?.trim())
        return if (PhoneMask.looksLikeNumber(c)) c else null
    }

    /**
     * Номер из URI собеседника.
     *
     * Схемы `tel:`, `sms:`, `smsto:` несут его напрямую. Ссылка на книгу контактов
     * (`content://com.android.contacts/…`) номера в себе не содержит — её разбирает
     * [fromContactUri], которому нужен доступ к книге.
     */
    fun fromUri(uri: String?): String? {
        val u = uri?.trim().orEmpty()
        if (u.isEmpty()) return null
        val scheme = u.substringBefore(':', "").lowercase()
        if (scheme != "tel" && scheme != "sms" && scheme != "smsto") return null
        // Uri.fromParts кодирует «+» как %2B, а разделители — как %20.
        return asNumber(decodePercent(u.substringAfter(':')))
    }

    /** Первый URI списка, из которого получается номер. */
    fun fromUris(uris: List<String?>): String? = uris.firstNotNullOfOrNull { fromUri(it) }

    /** Номер, если строка отправителя сама им является (обычная SMS показывает именно номер). */
    fun fromSender(sender: String?): String? = asNumber(sender)

    /**
     * Полная цепочка источников. `null` — номера нет нигде, отвечать по этому каналу нельзя.
     *
     * @param text время и текст входящего нужны последнему источнику — копии базы Google
     *   Messages, где сообщение ищется по содержимому.
     */
    fun resolve(context: Context, sender: String, uris: List<String?>,
                text: String, ts: Long): Found? {
        // Номер с кодом страны заканчивает поиск сразу. Местный («776 899 686» — так Google
        // Messages показывает чешские номера) запоминаем, но ищем дальше: маска стран, CRM и
        // склейка веток работают по международному виду, а он лежит в URI или в базе.
        var local: Found? = null
        fun take(n: String?, src: String): Found? {
            val f = n?.let { Found(it, src) } ?: return null
            if (f.number.startsWith("+")) return f
            if (local == null) local = f
            return null
        }

        take(fromSender(sender), "отправитель")?.let { return it }
        take(fromUris(uris), "URI уведомления")?.let { return it }
        // Сохранённый контакт: уведомление показывает имя из книги, номер берём оттуда же.
        take(uris.firstNotNullOfOrNull { fromContactUri(context, it) }, "книга (ссылка)")
            ?.let { return it }
        take(ContactUtil.numbersForName(context, sender).firstNotNullOfOrNull { asNumber(it) },
            "книга (имя)")?.let { return it }
        // Ничего не осталось: имя из RCS-профиля незнакомца. Номер знает только сам
        // мессенджер — читаем его базу через root-мост модуля.
        take(com.davnozdu.autoresponder.store.RcsFinder.numberFor(context, text, ts),
            "база Messages")?.let { return it }
        return local
    }

    /** Номер контакта по ссылке `content://com.android.contacts/…` из уведомления. */
    private fun fromContactUri(context: Context, uri: String?): String? {
        val u = uri?.trim().orEmpty()
        if (!u.startsWith("content://", ignoreCase = true)) return null
        if (!u.contains("contacts", ignoreCase = true)) return null
        return try {
            ContactUtil.numbersForContactUri(context, android.net.Uri.parse(u))
                .firstNotNullOfOrNull { asNumber(it) }
        } catch (_: Exception) { null }
    }

    /**
     * Раскодировать %XX.
     *
     * Uri.decode здесь не годится: он есть только на устройстве, а разбор URI покрыт
     * обычными юнит-тестами. Правило простое и на телефонных URI ошибиться негде.
     */
    private fun decodePercent(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val hex = if (ch == '%' && i + 2 < s.length) s.substring(i + 1, i + 3) else null
            val code = hex?.toIntOrNull(16)
            if (code != null) { out.append(code.toChar()); i += 3 } else { out.append(ch); i++ }
        }
        return out.toString()
    }
}
