package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.rules.SenderNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Извлечение номера собеседника из уведомления Google Messages.
 *
 * Случай из практики: клиент с номером +420608930525 писал по RCS, а в уведомлении вместо
 * номера стояло имя из его RCS-профиля («~Irina D'Arcy») — профиль отправителя, не контакт
 * из книги. Робот такое сообщение молча пропускал: номер брался только из строки
 * отправителя, а там был текст. Номер при этом лежит в самом уведомлении, в URI собеседника.
 */
class SenderNumberTest {

    @Test fun `номер берётся из tel-URI`() {
        assertEquals("+420608930525", SenderNumber.fromUri("tel:+420608930525"))
        assertEquals("+420608930525", SenderNumber.fromUri("tel:+420 608 930 525"))
        assertEquals("+420608930525", SenderNumber.fromUri("TEL:+420608930525"))
    }

    @Test fun `процентное кодирование плюса разворачивается`() {
        // Person.setUri часто собирают через Uri.fromParts — «+» там приезжает как %2B.
        assertEquals("+420608930525", SenderNumber.fromUri("tel:%2B420608930525"))
    }

    @Test fun `схемы sms и smsto тоже несут номер`() {
        assertEquals("+420608930525", SenderNumber.fromUri("smsto:+420608930525"))
        assertEquals("+420608930525", SenderNumber.fromUri("sms:+420608930525"))
    }

    @Test fun `чужие схемы номера не дают`() {
        assertNull(SenderNumber.fromUri("mailto:kdo@example.com"))
        assertNull(SenderNumber.fromUri("https://example.com/+420608930525"))
        // Ссылка на книгу контактов — номер за ней, но её разбирает отдельный путь с Context.
        assertNull(SenderNumber.fromUri("content://com.android.contacts/contacts/lookup/0r7-1/7"))
        assertNull(SenderNumber.fromUri(null))
        assertNull(SenderNumber.fromUri(""))
        assertNull(SenderNumber.fromUri("tel:"))
    }

    @Test fun `короткие и буквенные отправители номером не считаются`() {
        // «CeskaPosta» и прочие рассылки отвечать не надо: это не собеседник.
        assertNull(SenderNumber.fromUri("tel:CeskaPosta"))
        assertNull(SenderNumber.fromUri("tel:4321"))
    }

    @Test fun `местный номер без кода страны сохраняется как есть`() {
        // Google Messages показывает чешские номера и без кода («776 899 686»).
        // Дописать «+» здесь нельзя — получился бы несуществующий международный номер.
        assertEquals("776899686", SenderNumber.fromUri("tel:776 899 686"))
    }

    @Test fun `из списка URI берётся первый пригодный`() {
        assertEquals("+420608930525", SenderNumber.fromUris(
            listOf(null, "", "mailto:kdo@example.com", "tel:+420608930525", "tel:+420111222333")))
        assertNull(SenderNumber.fromUris(listOf(null, "mailto:kdo@example.com")))
        assertNull(SenderNumber.fromUris(emptyList()))
    }

    @Test fun `строка отправителя остаётся первым источником`() {
        assertEquals("+420602130788", SenderNumber.fromSender("+420 602 130 788"))
        assertEquals("+420602130788", SenderNumber.fromSender("+420602130788"))
        // Имя профиля RCS номером не является — дальше идут другие источники.
        assertNull(SenderNumber.fromSender("~Irina D'Arcy"))
        assertNull(SenderNumber.fromSender("CeskaPosta"))
    }
}
