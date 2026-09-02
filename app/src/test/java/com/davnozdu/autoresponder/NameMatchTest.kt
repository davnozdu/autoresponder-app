package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.rules.NameMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Мессенджер кладёт в уведомление имя из книги — сопоставление идёт по нему. */
class NameMatchTest {

    @Test fun `маска по тексту и по цифрам`() {
        assertTrue(NameMatch.matches("Мама Прага", "Мама*"))
        assertTrue(NameMatch.matches("+420 777 123 456", "+420*"))
        assertTrue(NameMatch.matches("+7 900 123-45-67", "*4567"))   // только через цифры
        assertTrue(NameMatch.matches("Иван П.", "Иван ?."))
        assertFalse(NameMatch.matches("Петр Новак", "Мама*"))
    }

    @Test fun `имя без маски — точное совпадение без регистра и собачки`() {
        assertTrue(NameMatch.matches("@petr_novak", "petr_novak"))
        assertTrue(NameMatch.matches("Пётр Новак", "пётр новак"))
        assertTrue(NameMatch.matches("Пётр  Новак", "Пётр Новак"))   // лишние пробелы
        assertFalse(NameMatch.matches("Пётр Новак", "Пётр"))
    }

    @Test fun `номер сравнивается по цифрам даже в списке имён`() {
        assertTrue(NameMatch.matches("+420 777 123 456", "+420777123456"))
    }

    @Test fun `пустые значения никогда не совпадают`() {
        assertFalse(NameMatch.matches(null, "Мама*"))
        assertFalse(NameMatch.matches("Мама", null))
        assertFalse(NameMatch.matches("  ", "Мама*"))
    }

    @Test fun `подсказка отличает номер от имени`() {
        assertTrue(NameMatch.describe("+420*").contains("маска"))
        assertTrue(NameMatch.describe("+420777123456").contains("цифрам"))
        assertTrue(NameMatch.describe("Пётр Новак").contains("имя"))
    }
}
