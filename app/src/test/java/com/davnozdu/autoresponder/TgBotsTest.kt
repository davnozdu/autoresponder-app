package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.store.TgBots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отличаем бота Telegram от человека по строке `users.name` из базы мессенджера.
 *
 * Все примеры взяты из живой базы устройства (12952 записи): правило признало ботами 92 из
 * 98 строк, содержащих «bot», и не задело ни одного человека, у которого «bot» оказался
 * внутри имени или ника.
 */
class TgBotsTest {

    @Test fun `бот виден по username, даже когда отображаемое имя молчит`() {
        // Ровно те случаи, из-за которых робот отвечал ботам: в уведомлении стоит
        // «BigTweak autopost» и «NAS-Hermes», и о «ботности» там ни слова.
        assertTrue(TgBots.isBotRow("bigtweak autopost;;;bigtweak_post_bot"))
        assertTrue(TgBots.isBotRow("nas-hermes;;;hermes_nascz_bot"))
        assertTrue(TgBots.isBotRow("bigtweak;;;bigtweak_bot"))
        assertTrue(TgBots.isBotRow("feed reader;;;feedreaderbot"))
        assertTrue(TgBots.isBotRow("экономика бот 💱;;;economika_bot"))
    }

    @Test fun `старый бот без суффикса в username виден по имени`() {
        // Telegram требует суффикс «bot» с давних пор, но у аккаунтов, заведённых раньше,
        // username бывает без него — зато имя о себе говорит.
        assertTrue(TgBots.isBotRow("votebot;;;vote"))
        assertTrue(TgBots.isBotRow("likebot;;;like"))
    }

    @Test fun `человек с «bot» внутри имени или ника ботом не считается`() {
        // Живые контакты с того же устройства.
        assertFalse(TgBots.isBotRow("надежда;;;nadezhda_boteva"))
        assertFalse(TgBots.isBotRow("сергей;;;nebotphotocot"))
        assertFalse(TgBots.isBotRow("robot 771;;;"))
        assertFalse(TgBots.isBotRow("skrobotaw;;;"))
        assertFalse(TgBots.isBotRow("слесарь-мейстер;;;v_stoptannyh_botinkah"))
    }

    @Test fun `пустая и кривая строка ботом не считается`() {
        assertFalse(TgBots.isBotRow(""))
        assertFalse(TgBots.isBotRow(";;;"))
    }

    @Test fun `отображаемое имя отделяется от ников`() {
        assertEquals("nas-hermes", TgBots.displayOf("nas-hermes;;;hermes_nascz_bot"))
        assertEquals("robot 771", TgBots.displayOf("robot 771;;;"))
        assertEquals("", TgBots.displayOf(""))
    }
}
