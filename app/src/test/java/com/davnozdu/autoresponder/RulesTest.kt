package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.call.Greeting
import com.davnozdu.autoresponder.respond.Dedup
import com.davnozdu.autoresponder.respond.SegmentBudget
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.rules.LangDetect
import com.davnozdu.autoresponder.rules.ScreeningPolicy
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

class ScreeningPolicyTest {

    private fun active(now: Int, dow: Int, days: Int = 124, start: Int = 9 * 60, end: Int = 18 * 60) =
        ScreeningPolicy.activeBySchedule(now, dow, days, start, end)

    @Test fun `рабочее время активно, вне его — нет`() {
        assertTrue(active(10 * 60, 3))            // вторник 10:00 — внутри 9-18
        assertFalse(active(20 * 60, 3))           // вторник 20:00 — вне окна
        assertFalse(active(8 * 60, 3))            // до открытия
        assertTrue(active(9 * 60, 3))             // граница начала включительно
        assertFalse(active(18 * 60, 3))           // граница конца — уже не активно
    }

    @Test fun `нерабочий день не активен даже в рабочие часы`() {
        assertFalse(active(10 * 60, 1))           // воскресенье
        assertFalse(active(10 * 60, 7))           // суббота
    }

    @Test fun `совпадающие границы расписания — скрининг никогда не активен`() {
        // Пустое окно [X, X) — намеренная тихая деградация: фича молча не работает,
        // если пользователь не поменял значения по умолчанию местами. См. Review Focus.
        assertFalse(active(12 * 60, 3, start = 9 * 60, end = 9 * 60))
        assertFalse(active(0, 3, start = 9 * 60, end = 9 * 60))
    }

    @Test fun `shouldScreen требует все три условия`() {
        assertTrue(ScreeningPolicy.shouldScreen(enabled = true, inWindow = true, skip = false))
        assertFalse(ScreeningPolicy.shouldScreen(enabled = false, inWindow = true, skip = false))
        assertFalse(ScreeningPolicy.shouldScreen(enabled = true, inWindow = false, skip = false))
        assertFalse(ScreeningPolicy.shouldScreen(enabled = true, inWindow = true, skip = true))
    }
}

class ScreeningBeepTest {

    @Test fun `нулевая и отрицательная длительность дают минимум один повтор`() {
        val zero = Greeting.repeatingBeepPcm(0)
        val negative = Greeting.repeatingBeepPcm(-500)
        assertTrue(zero.isNotEmpty())
        assertEquals(zero.size, negative.size)
    }

    @Test fun `бОльшая длительность даёт больше повторов`() {
        assertTrue(Greeting.repeatingBeepPcm(10_000).size > Greeting.repeatingBeepPcm(1_000).size)
    }

    @Test fun `повтор длины кратен единичному биппер-блоку`() {
        val one = Greeting.repeatingBeepPcm(1)          // минимум 1 повтор
        val many = Greeting.repeatingBeepPcm(one.size * 5) // должно дать >= 5 повторов по размеру
        assertEquals(0, many.size % one.size)
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

    @Test fun `разные сообщения с одинаковым Java hash не схлопываются`() {
        // Эти две строки имеют одинаковый String.hashCode() уже после lowercase.
        assertTrue(Dedup.claim("gojnwrph"))
        assertTrue(Dedup.claim("qjfxjcql"))
    }

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

class TimeWindowTest {

    @Test fun `тихий час через полночь`() {
        val start = 22 * 60; val end = 8 * 60
        assertTrue(com.davnozdu.autoresponder.rules.TimeWindow.contains(23 * 60, start, end))
        assertTrue(com.davnozdu.autoresponder.rules.TimeWindow.contains(3 * 60, start, end))
        assertTrue(com.davnozdu.autoresponder.rules.TimeWindow.contains(start, start, end))
        assertFalse(com.davnozdu.autoresponder.rules.TimeWindow.contains(end, start, end))
        assertFalse(com.davnozdu.autoresponder.rules.TimeWindow.contains(12 * 60, start, end))
    }

    @Test fun `окно внутри суток и равные границы`() {
        assertTrue(com.davnozdu.autoresponder.rules.TimeWindow.contains(13 * 60, 12 * 60, 14 * 60))
        assertFalse(com.davnozdu.autoresponder.rules.TimeWindow.contains(14 * 60, 12 * 60, 14 * 60))
        assertTrue(com.davnozdu.autoresponder.rules.TimeWindow.contains(5, 9 * 60, 9 * 60))
    }

    @Test fun `ближайшее наступление времени суток — сегодня или завтра`() {
        val tw = com.davnozdu.autoresponder.rules.TimeWindow
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, 8, 2, 23, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val next8 = tw.next(8 * 60, cal.timeInMillis)
        val c = java.util.Calendar.getInstance().apply { timeInMillis = next8 }
        assertEquals(8, c.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(3, c.get(java.util.Calendar.DAY_OF_MONTH))   // уже завтра
        assertTrue(next8 > cal.timeInMillis)
    }

    @Test fun `формат времени`() {
        assertEquals("08:00", com.davnozdu.autoresponder.rules.TimeWindow.fmt(8 * 60))
        assertEquals("22:30", com.davnozdu.autoresponder.rules.TimeWindow.fmt(22 * 60 + 30))
    }
}

class PricesTest {

    private val csv = """
        # устройство;услуга;цена;срок
        устройство;услуга;цена;срок
        iPhone 12;замена экрана;3500 Kč;1 день
        iPhone 13;замена экрана;4200 Kč;1 день
        MacBook Pro 14;замена батареи;5900 Kč;2 дня
    """.trimIndent()

    @Test fun `заголовок и комментарии не попадают в прайс`() {
        val rows = com.davnozdu.autoresponder.store.Prices.parse(csv)
        assertEquals(3, rows.size)
        assertEquals("iPhone 12", rows[0].device)
        assertEquals("3500 Kč", rows[0].price)
    }

    @Test fun `вопрос про деньги узнаётся на трёх языках`() {
        val p = com.davnozdu.autoresponder.store.Prices
        assertTrue(p.looksLikePriceQuestion("сколько стоит замена экрана?"))
        assertTrue(p.looksLikePriceQuestion("Kolik stojí výměna displeje?"))
        assertTrue(p.looksLikePriceQuestion("how much is a screen replacement"))
        assertFalse(p.looksLikePriceQuestion("когда будет готов?"))
        assertFalse(p.looksLikePriceQuestion(null))
    }

    @Test fun `подбираются строки по словам вопроса`() {
        val rows = com.davnozdu.autoresponder.store.Prices.parse(csv)
        val hit = com.davnozdu.autoresponder.store.Prices.match(rows, "сколько стоит замена батареи на macbook")
        assertTrue(hit.isNotEmpty())
        assertEquals("MacBook Pro 14", hit.first().device)
    }

    @Test fun `на неизвестное устройство строк нет — модель цену не назовёт`() {
        val rows = com.davnozdu.autoresponder.store.Prices.parse(csv)
        assertTrue(com.davnozdu.autoresponder.store.Prices.match(rows, "сколько стоит ремонт холодильника").isEmpty())
    }
}

class SmsCommandsTest {

    private val p = com.davnozdu.autoresponder.sms.SmsCommands

    @Test fun `команды понимаются на двух языках и в любом регистре`() {
        assertEquals("status", p.parse("STATUS"))
        assertEquals("status", p.parse(" статус "))
        assertEquals("off", p.parse("stop"))
        assertEquals("on", p.parse("Вкл"))
        assertEquals("pause", p.parse("pause 2"))
        assertEquals("digest", p.parse("сводка"))
    }

    @Test fun `обычное сообщение командой не считается`() {
        assertNull(p.parse("Здравствуйте, когда откроетесь?"))
        assertNull(p.parse(""))
        assertNull(p.parse(null))
        // Длинный текст, начинающийся со слова команды, — это письмо, а не команда.
        assertNull(p.parse("status " + "x".repeat(60)))
    }
}
