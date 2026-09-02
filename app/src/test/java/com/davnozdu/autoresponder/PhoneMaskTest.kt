package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.rules.PhoneMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сопоставление номеров — место, где ошибка не видна: клиент просто не получает ответа
 * (или получает тот, которого не должен). Поэтому правила зафиксированы тестом.
 */
class PhoneMaskTest {

    @Test fun `канонический вид дописывает плюс и убирает разделители`() {
        assertEquals("+420608210867", PhoneMask.canonical("+420 608 210 867"))
        assertEquals("+420608210867", PhoneMask.canonical("420 608 210 867"))
        assertEquals("+420608210867", PhoneMask.canonical("(420) 608-210-867"))
        assertEquals("+420608210867", PhoneMask.canonical("00420608210867"))
        assertEquals("+420608210867", PhoneMask.canonical("  +420608210867 "))
    }

    @Test fun `местный номер плюс не получает`() {
        // «+608210867» — несуществующий международный номер: лучше оставить как есть.
        assertEquals("608210867", PhoneMask.canonical("608 210 867"))
        assertEquals("0612345678", PhoneMask.canonical("0612345678"))
    }

    @Test fun `имя и маска не трогаются`() {
        assertEquals("Мама Прага", PhoneMask.canonical("Мама Прага"))
        assertEquals("+420*", PhoneMask.canonical("+420*"))
        assertEquals("*4567", PhoneMask.canonical("*4567"))
        assertEquals("", PhoneMask.canonical(null))
    }

    @Test fun `один номер в разных форматах — это один номер`() {
        assertTrue(PhoneMask.sameNumber("+31 6 1234 5678", "31612345678"))
        assertTrue(PhoneMask.sameNumber("+420777123456", "777 123 456"))
        assertFalse(PhoneMask.sameNumber("+420777123456", "+420777123999"))
        assertFalse(PhoneMask.sameNumber("Пётр", "+420777123456"))
    }

    @Test fun `маска страны учитывает только международные номера`() {
        val cz = listOf("+420")
        assertTrue(PhoneMask.matches("+420777123456", cz))
        assertTrue(PhoneMask.matches("00420777123456", cz))
        assertFalse(PhoneMask.matches("777123456", cz))      // без кода страны — не наш
        assertFalse(PhoneMask.matches("+31612345678", cz))
    }

    @Test fun `исключения ловят номер в любом формате и по маске`() {
        val list = listOf("+420 777 123 456", "*4567")
        assertTrue(PhoneMask.isExcluded("+420777123456", list))
        assertTrue(PhoneMask.isExcluded("777123456", list))
        assertTrue(PhoneMask.isExcluded("+7 900 123-45-67", list))   // по маске, через цифры
        assertFalse(PhoneMask.isExcluded("+420608210867", list))
    }

    @Test fun `буквенный отправитель — не номер`() {
        assertTrue(PhoneMask.isAlphanumericSender("Sberbank"))
        assertFalse(PhoneMask.isAlphanumericSender("+420777123456"))
        assertFalse(PhoneMask.looksLikeNumber("Пётр Новак"))
        assertTrue(PhoneMask.looksLikeNumber("+420 777 123 456"))
    }
}
