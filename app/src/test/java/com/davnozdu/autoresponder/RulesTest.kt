package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.respond.Dedup
import com.davnozdu.autoresponder.respond.SegmentBudget
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.LangDetect
import com.davnozdu.autoresponder.store.BlackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {

    private fun window(now: Int, start: Int, end: Int) =
        ClosedState.closedBySchedule(0, now, 2, 0, 0, 0, start, end)

    @Test fun `окно через полночь закрывает вечер и ночь`() {
        assertTrue(window(23 * 60, 18 * 60, 9 * 60))     // 23:00 внутри 18:00–09:00
        assertTrue(window(3 * 60, 18 * 60, 9 * 60))      // 03:00 тоже
        assertFalse(window(12 * 60, 18 * 60, 9 * 60))    // полдень — открыто
        assertTrue(window(18 * 60, 18 * 60, 9 * 60))     // граница начала включительно
        assertFalse(window(9 * 60, 18 * 60, 9 * 60))     // граница конца — уже открыто
    }

    @Test fun `обычное окно внутри суток`() {
        assertTrue(window(13 * 60, 12 * 60, 14 * 60))
        assertFalse(window(11 * 60, 12 * 60, 14 * 60))
    }

    @Test fun `совпадающие границы — закрыто круглые сутки`() {
        assertTrue(window(0, 9 * 60, 9 * 60))
        assertTrue(window(15 * 60, 9 * 60, 9 * 60))
    }

    @Test fun `рабочие дни и часы`() {
        // Calendar.DAY_OF_WEEK: вс = 1 … сб = 7. Маска «пн–пт» = биты 2..6.
        val monFri = (2..6).fold(0) { acc, d -> acc or (1 shl d) }
        fun work(now: Int, dow: Int) =
            ClosedState.closedBySchedule(1, now, dow, monFri, 9 * 60, 18 * 60, 0, 0)
        assertFalse(work(10 * 60, 3))          // вторник, 10:00 — открыто
        assertTrue(work(20 * 60, 3))           // вторник, 20:00 — закрыто
        assertTrue(work(10 * 60, 1))           // воскресенье — закрыто весь день
        assertTrue(work(10 * 60, 7))           // суббота — тоже
        assertFalse(work(9 * 60, 6))           // пятница ровно в 9:00 — открыто
        assertTrue(work(18 * 60, 6))           // в 18:00 уже закрыто
    }
}

class SegmentBudgetTest {

    @Test fun `кириллица уходит в UCS-2 и бюджет вдвое меньше`() {
        assertFalse(SegmentBudget.isGsm7("Здравствуйте"))
        assertTrue(SegmentBudget.isGsm7("Hello, we are closed"))
        assertEquals(160, SegmentBudget.budgetForText("Hello", 1))
        assertEquals(70, SegmentBudget.budgetForText("Привет", 1))
        assertEquals(153 * 2, SegmentBudget.budgetForText("Hello", 2))
        assertEquals(67 * 2, SegmentBudget.budgetForText("Привет", 2))
    }

    @Test fun `чешская диакритика — тоже UCS-2`() {
        assertFalse(SegmentBudget.isGsm7("Dobrý den, zakázka"))
        assertTrue(SegmentBudget.isGsm7("Dobry den, zakazka"))
    }

    @Test fun `обрезка укладывается в бюджет и не рвёт слово`() {
        val long = "слово ".repeat(40).trim()
        val cut = SegmentBudget.clampToBudget(long, 1)
        assertTrue(SegmentBudget.encodedLength(cut) <= 70)
        assertFalse(cut.endsWith(" "))
        assertTrue(long.startsWith(cut))
    }

    @Test fun `короткий текст не трогаем`() {
        assertEquals("Готово", SegmentBudget.clampToBudget("Готово", 1))
    }

    @Test fun `эмодзи не разрывается пополам`() {
        val text = "a".repeat(69) + "👍"
        val cut = SegmentBudget.clampToBudget(text, 1)
        assertFalse(Character.isHighSurrogate(cut.lastOrNull() ?: ' '))
    }
}

class LangDetectTest {

    @Test fun `кириллица — русский, диакритика — чешский`() {
        assertEquals("ru", LangDetect.detect("Когда будет готов?", "cs"))
        assertEquals("cs", LangDetect.detect("Dobrý den", "en"))
    }

    @Test fun `чешский без диакритики узнаётся по словам`() {
        // Ровно тот случай, ради которого список слов и появился.
        assertEquals("cs", LangDetect.detect("kdy bude hotovo", "en"))
        assertEquals("en", LangDetect.detect("when will it be ready", "cs"))
    }

    @Test fun `пусто и цифры — язык по умолчанию`() {
        assertEquals("cs", LangDetect.detect(null, "cs"))
        assertEquals("cs", LangDetect.detect("   ", "cs"))
        assertEquals("cs", LangDetect.detect("12345", "cs"))
    }
}

class DedupTest {

    @Test fun `один и тот же текст второй раз не проходит`() {
        val t = "Здравствуйте, когда будет готов заказ ${System.nanoTime()}"
        assertTrue(Dedup.claim(t))
        assertFalse(Dedup.claim(t))
        assertFalse(Dedup.claim("  $t\n"))   // разбивка пробелов не создаёт новый ключ
    }

    @Test fun `разные тексты не мешают друг другу`() {
        assertTrue(Dedup.claim("первый ${System.nanoTime()}"))
        assertTrue(Dedup.claim("второй ${System.nanoTime()}"))
    }
}

class BlackEntryTest {

    private fun entry(until: Long) = BlackEntry(1, "+420608210867", null, false, null, untilTs = until)

    @Test fun `навсегда не истекает`() {
        assertFalse(entry(0L).expired(now = Long.MAX_VALUE))
    }

    @Test fun `срок сравнивается с текущим моментом`() {
        val t = 1_000_000L
        assertFalse(entry(t).expired(now = t - 1))
        assertTrue(entry(t).expired(now = t))
        assertTrue(entry(t).expired(now = t + 1))
    }
}

class CrmFlowTest {

    @Test fun `вопрос о статусе — это предмет плюс состояние`() {
        assertTrue(com.davnozdu.autoresponder.crm.CrmFlow.looksLikeStatusQuestion("когда будет готов мой заказ?"))
        assertTrue(com.davnozdu.autoresponder.crm.CrmFlow.looksLikeStatusQuestion("Kdy bude hotova zakazka?"))
        assertTrue(com.davnozdu.autoresponder.crm.CrmFlow.looksLikeStatusQuestion("что с телефоном?"))
    }

    @Test fun `вопрос о часах работы статусом не отвечаем`() {
        // «когда» есть, предмета нет — раньше на это уходил бы статус заказа.
        assertFalse(com.davnozdu.autoresponder.crm.CrmFlow.looksLikeStatusQuestion("когда вы работаете?"))
        assertFalse(com.davnozdu.autoresponder.crm.CrmFlow.looksLikeStatusQuestion("здравствуйте"))
    }

    @Test fun `длинное письмо — не «ну что там»`() {
        val long = "заказ готов ".repeat(50)
        assertFalse(com.davnozdu.autoresponder.crm.CrmFlow.looksLikeStatusQuestion(long))
    }

    @Test fun `согласие отличается от слов, которые с него начинаются`() {
        val f = com.davnozdu.autoresponder.crm.CrmFlow
        assertEquals("", f.yesWithQuestion("да"))
        assertEquals("а когда заберу?", f.yesWithQuestion("Да, а когда заберу?"))
        assertNull(f.yesWithQuestion("даже не знаю"))
        assertNull(f.yesWithQuestion("нет, спасибо"))
    }
}
