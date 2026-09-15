package com.davnozdu.autoresponder.notif

import java.util.regex.Pattern

/**
 * Что в уведомлении мессенджера сообщением НЕ является.
 *
 * Правила вынесены из [NotifResponder] отдельно: они чисто текстовые, ошибка в них стоит
 * клиенту лишнего ответа (или потерянного вопроса), и проверяются они тестами, а не
 * наблюдением за живой перепиской.
 *
 * Случай, с которого это началось (WhatsApp, 13.09): клиент прислал фото и написал
 * «Пожалуйста» — и получил четыре автоответа подряд. WhatsApp не постит новое уведомление
 * на каждое фото, он ОБНОВЛЯЕТ прежнее, и текст в нём меняется: «📷 1 photo» → «📷 2 photos»
 * → «📷 3 photos». Для анти-дубля это три разных сообщения. Следом клиент поставил реакцию
 * на наш же ответ («Reacted 😂 to "AI: …"») — и она тоже пришла как входящее.
 *
 * ВАЖНО про юникод (14.09). Регистр задаётся ФЛАГАМИ компиляции, а не префиксом `(?iuU)`
 * внутри шаблона, и в шаблонах нет ни `\w`, ни `\b`.
 *
 * На Android `java.util.regex` реализован поверх ICU (`com.android.icu.util.regex.PatternNative`),
 * а ICU флага `U` не знает: он отвергает ВЕСЬ шаблон с `U_REGEX_INVALID_FLAG`, и
 * `Pattern.compile` кидает `PatternSyntaxException` (флаг `Pattern.UNICODE_CHARACTER_CLASS`
 * там же отвергается сообщением «UNICODE_CHARACTER_CLASS flag not supported»). На десктопной
 * JVM, где идут юнит-тесты, тот же шаблон компилируется без нареканий — поэтому CI был
 * зелёный, а на телефоне падала инициализация этого объекта. Дальше по цепочке
 * `NotifResponder.isPlaceholder` → `logHistory`/`process` умирали на `NoClassDefFoundError`,
 * и с 14.09 09:58 ни одно сообщение мессенджера не получило ответа и не попало в журнал.
 *
 * `\w` и `\b` были «юникодными» на JVM именно благодаря `U`; здесь вместо них [W] —
 * явный класс, который значит одно и то же в обоих движках.
 */
object NotifText {

    /** Регистр — флагами: `UNICODE_CASE` поддерживают и ICU, и JVM (в отличие от `U`). */
    private fun ci(pattern: String): Regex =
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).toRegex()

    /** Буква, цифра или подчёркивание — замена `\w`, одинаковая на ICU и на JVM. */
    private const val W = "[\\p{L}\\p{N}_]"

    /** Значок вложения в начале строки: WhatsApp и Telegram ставят его перед подписью. */
    private val LEAD_ICON = Regex("^[\\p{So}\\uFE0F\\u200D\\s]+")

    /** Слова, которыми мессенджер называет вложение вместо текста. */
    private const val KIND =
        "photos?|images?|pictures?|videos?|gifs?|stickers?|audios?|documents?|files?|location|contact" +
        "|фото$W*|изображени$W+|картинк$W+|видео|гиф$W*|стикер$W*|аудио|документ$W*|файл$W*" +
        "|геолокаци$W+|местоположени$W+|контакт$W*"

    /**
     * Уведомление о вложении без подписи.
     *
     * Сравниваем со ВСЕЙ строкой, а не с её началом: «📷 Вот смотрю тут видео всякие» —
     * это фото с подписью, то есть настоящая реплика клиента, и на неё отвечать надо.
     */
    private val ATTACHMENT = ci(
        "^(" +
            "(sent\\s+(you\\s+)?an?\\s+)?($KIND)" +
            "|\\d+\\s+($KIND)" +
            "|(voice|audio|video)\\s+(message|note)" +
            "|(голосово$W+|аудио|видео)\\s+(сообщение|запись)" +
            "|(отправка|отправляется|sending)\\s+$W+" +
        ")\\.{0,3}$")

    /**
     * «Имя прислал(а) вам 2 фото» — так вложение подписывает Telegram, а WhatsApp Business
     * при скрытом содержимом. Имя впереди произвольное, поэтому ловим по хвосту.
     *
     * Название вложения обязано быть КОНЦОМ строки: иначе «Отправил вам документы по почте»
     * (обычная фраза клиента) сошло бы за вложение и осталось без ответа.
     */
    private val ATTACHMENT_TAIL = ci(
        "(прислал|отправил|послал)(а|\\(а\\))?\\s+(вам\\s+)?\\d*\\s*($KIND)\\s*$" +
        "|sent\\s+(you\\s+)?\\d*\\s*($KIND)\\s*$")

    /**
     * Реакция на сообщение — не реплика.
     *
     * Клиент ставит её чаще всего на НАШ же ответ, и робот отвечал на собственный текст:
     * «Reacted 😂 to "AI: Спасибо за фото…"». Анти-петля по префиксу ИИ тут не помогает —
     * строка начинается со слова «Reacted», а не с префикса.
     *
     * Вторая форма — голый значок вместо слова («😂 to "…"»). Значок описан как «не буква и
     * не пробел», чтобы короткое слово перед «на» («Дай на "ремонт"») сюда не попало.
     *
     * `(?!$W)` — это бывшая граница слова `\b`: «reacted» ловим, «reactedly» нет.
     */
    private val REACTION = ci(
        "^(reacted(?!$W)|отреагировал|поставил[аи]?\\s+реакц|liked(?!$W)|понравилось(?!$W))" +
        "|^[^\\p{L}\\s]{1,3}\\s+(to|на)\\s+[\"«]")

    /** Счётчик непрочитанных вместо текста: содержания в нём нет. */
    private val BUNDLE_COUNT = ci(
        "^\\d+\\s+(new\\s+)?(messages?|сообщени$W+)$|^new message$|^новое сообщение$")

    private fun core(text: String): String = text.trim().replace(LEAD_ICON, "").trim()

    fun isBundleCount(text: String): Boolean = BUNDLE_COUNT.containsMatchIn(core(text))

    fun isAttachment(text: String): Boolean {
        val c = core(text)
        if (c.isEmpty()) return false
        return ATTACHMENT.matches(c) || ATTACHMENT_TAIL.containsMatchIn(c)
    }

    /**
     * Смотрим на исходную строку, а не на [core]: у формы «😂 to "…"» значок и есть вся
     * примета реакции, и срезать его вместе с ведущей иконкой вложения нельзя.
     */
    fun isReaction(text: String): Boolean = REACTION.containsMatchIn(text.trim())

    /** Отвечать не на что: пусто, вложение без подписи, счётчик или реакция. */
    fun isNotAMessage(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || isBundleCount(t) || isAttachment(t) || isReaction(t)
    }

    /**
     * Исходники шаблонов — для теста, который стережёт совместимость с ICU
     * (см. `NotifTextTest`: ни встроенных флагов, ни `\w`, ни `\b`).
     */
    internal val patternSources: List<String>
        get() = listOf(LEAD_ICON.pattern, ATTACHMENT.pattern, ATTACHMENT_TAIL.pattern,
            REACTION.pattern, BUNDLE_COUNT.pattern)
}
