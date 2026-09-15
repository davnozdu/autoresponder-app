package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.notif.NotifText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор текста уведомления мессенджера.
 *
 * Случай из практики (WhatsApp, 13.09): клиент прислал фото и написал «Пожалуйста» —
 * и получил четыре автоответа подряд, два из них слово в слово одинаковые. WhatsApp не
 * постит новое уведомление на каждое фото, он обновляет прежнее, и текст в нём меняется:
 * «📷 1 photo» → «📷 2 photos» → «📷 3 photos». Анти-дубль считает их разными сообщениями.
 */
class NotifTextTest {

    @Test fun `уведомление о фото без подписи сообщением не является`() {
        assertTrue(NotifText.isAttachment("📷 Sent a photo"))
        assertTrue(NotifText.isAttachment("📷 Photo"))
        assertTrue(NotifText.isAttachment("📷 1 photo"))
        assertTrue(NotifText.isAttachment("📷 2 photos"))
        assertTrue(NotifText.isAttachment("📷 3 photos"))
        assertTrue(NotifText.isAttachment("Фото"))
        assertTrue(NotifText.isAttachment("🎥 Video"))
        assertTrue(NotifText.isAttachment("🎤 Voice message"))
        assertTrue(NotifText.isAttachment("Голосовое сообщение"))
        assertTrue(NotifText.isAttachment("Отправка видео..."))
        assertTrue(NotifText.isAttachment("Мама Нидерланды прислал(а) вам 2 фото"))
    }

    @Test fun `фото С ПОДПИСЬЮ отвечать надо`() {
        // Подпись — настоящая реплика клиента: пропустить её значит не ответить на вопрос.
        assertFalse(NotifText.isAttachment("📷 Вот смотрю тут видео всякие. Справила робота"))
        assertFalse(NotifText.isAttachment("📷 Сколько будет стоить ремонт такого экрана?"))
        assertFalse(NotifText.isAttachment("Пришлю фото завтра"))
        assertFalse(NotifText.isAttachment("На видео шеф-повар делает хрустящие чипсы"))
    }

    @Test fun `реакция на сообщение ответом не считается`() {
        // Реакцию клиент ставит чаще всего на НАШ же ответ — робот отвечал сам себе.
        assertTrue(NotifText.isReaction("""Reacted 😂 to "AI: Спасибо за фото. Сейчас нерабочее время."""))
        assertTrue(NotifText.isReaction("""😂 to "AI: Спасибо за фото""""))
        assertTrue(NotifText.isReaction("""Отреагировал(а) 👍 на "Здравствуйте""""))
        assertFalse(NotifText.isReaction("Реакция клиента была бурной, спасибо"))
        assertFalse(NotifText.isReaction("Добрый день, как там компьютер?"))
    }

    @Test fun `счётчик непрочитанных содержания не несёт`() {
        assertTrue(NotifText.isBundleCount("3 new messages"))
        assertTrue(NotifText.isBundleCount("2 messages"))
        assertTrue(NotifText.isBundleCount("new message"))
        assertTrue(NotifText.isBundleCount("5 сообщений"))
        assertFalse(NotifText.isBundleCount("2 экрана надо заменить"))
    }

    @Test fun `настоящая реплика клиента проходит`() {
        assertFalse(NotifText.isNotAMessage("Пожалуйста"))
        assertFalse(NotifText.isNotAMessage("Привет"))
        assertFalse(NotifText.isNotAMessage("Добрый день, как там компьютер? Когда ожидать?"))
        assertFalse(NotifText.isNotAMessage("Dobry den vy opravujete klávesnice ?"))
        assertFalse(NotifText.isNotAMessage("У тебя автоответчик заглючил"))
    }

    @Test fun `пустое сообщением не является`() {
        assertTrue(NotifText.isNotAMessage(""))
        assertTrue(NotifText.isNotAMessage("   "))
    }

    /**
     * Стража от повтора поломки 14.09.
     *
     * Юнит-тесты идут на десктопной JVM, а на телефоне `java.util.regex` реализован поверх
     * ICU. ICU не знает встроенного флага `U`, и шаблон `(?iuU)…` там не компилируется вовсе:
     * объект `NotifText` падал на инициализации, а вместе с ним молча умирала вся обработка
     * сообщений мессенджеров. Поведенческий тест этого не ловит — ловит только запрет на
     * конструкции, которые у двух движков значат разное.
     */
    @Test fun `шаблоны не зависят от флага UNICODE_CHARACTER_CLASS`() {
        NotifText.patternSources.forEach { p ->
            assertFalse("встроенные флаги ICU не поддерживает: $p", Regex("""\(\?[a-zA-Z]*[uU]""").containsMatchIn(p))
            assertFalse("""\w значит разное на ICU и JVM: $p""", p.contains("""\w"""))
            assertFalse("""\b значит разное на ICU и JVM: $p""", p.contains("""\b"""))
        }
    }
}
