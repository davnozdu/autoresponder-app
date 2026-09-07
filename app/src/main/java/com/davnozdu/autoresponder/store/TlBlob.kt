package com.davnozdu.autoresponder.store

/**
 * Разбор TL-блобов Telegram (`messages_v2.data`, `users.data` в `cache4.db`).
 *
 * Telegram кладёт сообщение целиком сериализованным объектом TL, а не текстом в колонке.
 * Полный разбор `TL_message` писать нельзя: между `peer_id` и `date` лежат необязательные
 * вложенные объекты (`fwd_from`, `reply_to`, `via_bot_id`), структура которых меняется от
 * слоя к слою — такой парсер сломается на ближайшем обновлении Telegram.
 *
 * Поэтому опираемся на инвариант, который в TL держится всегда: `message:string` идёт
 * НЕПОСРЕДСТВЕННО за `date:int`, а дата у нас уже есть отдельной колонкой. Ищем в блобе
 * её little-endian запись и читаем следом TL-строку.
 *
 * Берём ПЕРВОЕ вхождение, за которым лежит корректная TL-строка, и принимаем её как есть —
 * в том числе ПУСТУЮ. Пустая строка и значит «текста нет»: у медиа без подписи `message`
 * пуст, а сразу за ним лежит объект вложения со своей датой. Правило «последнее вхождение»
 * на таких сообщениях уезжало внутрь вложения, и в журнал уходил его mime-тип: на базе
 * устройства так появилось 29 записей «application/pdf», «video/mp4», «image/png», а у
 * одного сообщения настоящая подпись оказалась затёрта на «image/png».
 *
 * Проверено на всей базе устройства (25 534 записи): правило «первое вхождение» даёт
 * 2 565 текстов в личных диалогах и ни одного mime-мусора, правило «последнее» — 2 587
 * текстов, из них 29 мусорных.
 */
object TlBlob {

    /** `messageService` — вместо текста лежит `action` (звонок, вход в чат). */
    private const val CTOR_SERVICE = 0x7A800E0A

    /** Служебное сообщение: текста в нём нет и в историю оно не идёт. */
    fun isService(blob: ByteArray): Boolean = readInt(blob, 0) == CTOR_SERVICE

    /**
     * Текст сообщения или null, если его нет (медиа без подписи, служебное, битый блоб).
     * @param date значение колонки `messages_v2.date` — якорь поиска.
     */
    fun messageText(blob: ByteArray, date: Int): String? {
        if (blob.size < 8) return null
        val anchor = byteArrayOf(
            (date and 0xFF).toByte(), ((date shr 8) and 0xFF).toByte(),
            ((date shr 16) and 0xFF).toByte(), ((date shr 24) and 0xFF).toByte()
        )
        var from = 0
        while (true) {
            val at = indexOf(blob, anchor, from)
            if (at < 0) return null
            // Первая позиция, за которой стоит РАЗБИРАЕМАЯ строка, и есть `message`.
            // Дальше не идём: следующее вхождение даты лежит уже внутри вложения.
            readString(blob, at + 4)?.let { return it.first.ifEmpty { null } }
            from = at + 1
        }
    }

    /**
     * Телефон контакта из `users.data`.
     *
     * В `TL_user` телефон — обычная TL-строка среди имени и username'ов, и её порядковый
     * номер зависит от того, какие поля выставлены во flags. Опознаём по содержимому:
     * строка из одних цифр длиной 8–15 — это номер и ничего другого.
     */
    fun userPhone(blob: ByteArray): String? {
        var i = 0
        while (i < blob.size) {
            val r = readString(blob, i)
            if (r != null) {
                val s = r.first
                if (s.length in 8..15 && s.all { it.isDigit() }) return s
            }
            i++
        }
        return null
    }

    /** TL-строка с позиции [i]: (текст, позиция за ней с учётом выравнивания). */
    private fun readString(b: ByteArray, i: Int): Pair<String, Int>? {
        if (i < 0 || i >= b.size) return null
        val n = b[i].toInt() and 0xFF
        val len: Int
        val start: Int
        when {
            n == 254 -> {
                if (i + 4 > b.size) return null
                len = (b[i + 1].toInt() and 0xFF) or ((b[i + 2].toInt() and 0xFF) shl 8) or
                    ((b[i + 3].toInt() and 0xFF) shl 16)
                start = i + 4
            }
            n < 254 -> { len = n; start = i + 1 }
            else -> return null   // 255 в TL не встречается
        }
        val end = start + len
        if (end > b.size) return null
        val text = try {
            String(b, start, len, Charsets.UTF_8)
        } catch (_: Exception) { return null }
        // Строка, собранная из произвольных байтов, декодируется в «текст» с U+FFFD.
        // Такое совпадение — не сообщение, а мусор рядом с якорем.
        if (text.contains('�')) return null
        val pad = (4 - ((end - i) % 4)) % 4
        return text to (end + pad)
    }

    private fun readInt(b: ByteArray, i: Int): Int {
        if (i + 4 > b.size) return 0
        return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
