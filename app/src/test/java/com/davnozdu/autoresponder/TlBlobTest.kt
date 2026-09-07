package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.store.TlBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор TL-блобов Telegram (`messages_v2.data`).
 *
 * Образцы сняты с реального устройства (`cache4.db`) и обрезаны до значимой части.
 * Проверялось на всей базе: 25 534 записи, ни одного сбоя разбора.
 */
class TlBlobTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Реальное исходящее сообщение: "Я уже спать ложусь." */
    private val outgoing = hex(
        "D3B90076" +                 // ctor TL_message
        "02010000" + "00000000" +    // flags (out|from_id), flags2
        "EB870300" +                 // id
        "22175159" + "8CE5010E00000000" +  // from_id: peerUser
        "22175159" + "661E630700000000" +  // peer_id: peerUser
        "C3B29D6A" +                 // date = 1788719811
        "22" + "D0AF20D183D0B6D0B520D181D0BFD0B0D182D18C20D0BBD0BED0B6D183D181D18C2E" +
        "00" + "0120" + "0000"       // паддинг и хвост
    )

    /** Реальное исходящее: "Нет, сидим с алексом" */
    private val outgoing2 = hex(
        "D3B90076" + "02030000" + "00000000" + "E9870300" +
        "22175159" + "8CE5010E00000000" +
        "22175159" + "59716D1000000000" +
        "2FA29D6A" +                 // date = 1788715567
        "24" + "D09DD0B5D1822C20D181D0B8D0B4D0B8D0BC20D18120D0B0D0BBD0B5D0BAD181D0BED0BC" +
        "000000" + "2063ED3D01200000"
    )

    @Test fun `текст читается по якорю date`() {
        assertEquals("Я уже спать ложусь.", TlBlob.messageText(outgoing, 1788719811))
        assertEquals("Нет, сидим с алексом", TlBlob.messageText(outgoing2, 1788715567))
    }

    @Test fun `чужая дата не даёт текста`() {
        assertNull(TlBlob.messageText(outgoing, 1700000000))
    }

    @Test fun `битый и пустой блоб не роняют разбор`() {
        assertNull(TlBlob.messageText(ByteArray(0), 1788719811))
        assertNull(TlBlob.messageText(hex("D3B90076FFFF"), 1788719811))
    }

    @Test fun `служебное сообщение отсекается по конструктору`() {
        // messageService: вместо message:string лежит action — текста там нет,
        // а «Missed voice call» из такого сообщения раньше уходил в историю как реплика.
        assertEquals(true, TlBlob.isService(hex("0A0E807A0000")))
        assertEquals(false, TlBlob.isService(outgoing))
    }

    @Test fun `длинная строка кодируется четырьмя байтами длины`() {
        val body = "x".repeat(300)
        val blob = hex("D3B90076") + intLe(1788719811) +
            byteArrayOf(254.toByte(), 300.toByte(), 1, 0) + body.toByteArray() + ByteArray(4)
        assertEquals(body, TlBlob.messageText(blob, 1788719811))
    }

    /** Телефон контакта лежит в `users.data` отдельной TL-строкой из одних цифр. */
    @Test fun `телефон достаётся из блоба пользователя`() {
        val user = hex(
            "83CCB8B1" + "7B180012" + "10000000" + "F4CB180000000000" +
            "F738057D57FFADEC" +
            "13" + "D094D0B0D188D0B020D09CD0B0D186D0B0D0BA" +   // имя
            "0B" + "64617269616D617473616B" +                    // username
            "0C" + "343230373738303739303738" + "000000"         // телефон
        )
        assertEquals("420778079078", TlBlob.userPhone(user))
        assertNull(TlBlob.userPhone(hex("83CCB8B10102")))
    }

    private fun intLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
}
