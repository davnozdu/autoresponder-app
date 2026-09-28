package com.davnozdu.autoresponder

import com.davnozdu.autoresponder.call.GreetingTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Кросс-корреляция по огибающей громкости — единственный практичный способ проверить, что
 * findExcludeWindow/applyExclude ведут себя безопасно (не режут речь клиента), раз живого
 * звонка в этой среде нет. Сигналы синтетические: важна только форма RMS-огибающей по
 * кадрам (FRAME_MS=20 внутри GreetingTrim), не реальное звучание, поэтому "приветствие" и
 * "клиент" строятся как псевдослучайный шум с управляемой амплитудой по сегментам —
 * различимые по ритму громкости паттерны, как и у настоящей речи.
 */
class GreetingTrimTest {
    private val rate = 16000

    /** [segmentsMs] — список (длительность_мс, амплитуда 0..1). seed — для воспроизводимости
     *  и чтобы "приветствие" и "клиент" не совпадали по конкретным сэмплам. */
    private fun buildSignal(segmentsMs: List<Pair<Int, Double>>, seed: Int): ShortArray {
        val rnd = Random(seed)
        val totalSamples = segmentsMs.sumOf { it.first * rate / 1000 }
        val out = ShortArray(totalSamples)
        var pos = 0
        for ((durMs, amp) in segmentsMs) {
            val n = durMs * rate / 1000
            repeat(n) {
                if (pos < out.size) out[pos++] = ((rnd.nextDouble(-1.0, 1.0)) * amp * 20000).toInt().toShort()
            }
        }
        return out
    }

    private fun silence(ms: Int) = ShortArray(ms * rate / 1000)

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, out, pos, p.size); pos += p.size }
        return out
    }

    // "Приветствие" — характерный ритм: два всплеска громкости с паузой между ними, 4с всего.
    private fun greeting(seed: Int = 1) = buildSignal(
        listOf(1500 to 0.8, 300 to 0.05, 1200 to 0.6, 1000 to 0.05), seed)

    // "Клиент" — совсем другой ритм всплесков, 3с, другой seed (другие конкретные сэмплы).
    private fun clientSpeech(seed: Int = 2) = buildSignal(
        listOf(400 to 0.9, 200 to 0.1, 400 to 0.9, 200 to 0.1, 600 to 0.7, 1200 to 0.1), seed)

    @Test fun `находит приветствие в записи и режет с запасом внутрь, не по самому краю`() {
        val greet = greeting()
        val client = clientSpeech()
        // Приветствие звучит в записи чуть тише (эмулируем разницу в усилении тракта) — не 1:1
        val greetInRecording = ShortArray(greet.size) { (greet[it] * 0.7).toInt().toShort() }
        val recording = concat(silence(500), greetInRecording, silence(300), client, silence(500))
        val greetStart = 500 * rate / 1000
        val greetEnd = greetStart + greet.size

        val window = GreetingTrim.findExcludeWindow(recording, rate, greet, rate)
        assertNotNull("должно найти совпадение", window)
        window!!
        // Найденное окно внутри истинных границ приветствия (с запасом внутрь, не наружу)
        assertTrue("начало окна не раньше истинного начала", window.first >= greetStart - rate / 10)
        assertTrue("начало окна позже истинного начала (сужение внутрь)", window.first > greetStart)
        assertTrue("конец окна не позже истинного конца", window.last < greetEnd)
        assertTrue("конец окна раньше истинного конца (сужение внутрь)", window.last <= greetEnd + rate / 10)
    }

    @Test fun `вырезание не трогает речь клиента — сэмплы совпадают побайтово`() {
        val greet = greeting()
        val client = clientSpeech()
        val recording = concat(silence(500), greet, silence(300), client, silence(500))
        val clientStart = 500 * rate / 1000 + greet.size + 300 * rate / 1000

        val window = GreetingTrim.findExcludeWindow(recording, rate, greet, rate)
        assertNotNull(window)
        val trimmed = GreetingTrim.applyExclude(recording, window)
        assertTrue("запись должна укоротиться", trimmed.size < recording.size)

        // Ищем участок клиента в обрезанной записи по прямому сравнению — он обязан остаться
        // ПОБАЙТОВО тем же (не тронут, не смещён внутри себя, не искажён).
        val idx = indexOfSubarray(trimmed, client)
        assertTrue("речь клиента должна остаться в обрезанной записи целиком и неизменно", idx >= 0)
        // На всякий случай — не только "нашлось где-то", а что это разумно рядом с исходной
        // позицией за вычетом длины вырезанного окна.
        val expectedApprox = clientStart - (window!!.last + 1 - window.first)
        assertTrue(kotlin.math.abs(idx - expectedApprox) < rate) // допуск 1с на округления кадров
    }

    @Test fun `эталона нет в записи — не режет вовсе`() {
        val greet = greeting()
        val unrelated = buildSignal(listOf(5000 to 0.5), seed = 99)
        val window = GreetingTrim.findExcludeWindow(unrelated, rate, greet, rate)
        assertNull("не должно быть ложного совпадения на не связанном сигнале", window)
    }

    @Test fun `тишина вместо записи — не режет и не падает`() {
        val greet = greeting()
        val window = GreetingTrim.findExcludeWindow(silence(6000), rate, greet, rate)
        assertNull(window)
    }

    @Test fun `эталон длиннее записи — не падает, возвращает null`() {
        val greet = greeting()
        val shortRec = silence(500)
        val window = GreetingTrim.findExcludeWindow(shortRec, rate, greet, rate)
        assertNull(window)
    }

    @Test fun `applyExclude с null окном возвращает запись как есть`() {
        val rec = clientSpeech()
        val out = GreetingTrim.applyExclude(rec, null)
        assertEquals(rec.size, out.size)
        assertTrue(rec.contentEquals(out))
    }

    @Test fun `applyExclude вырезает ровно указанный диапазон`() {
        val rec = ShortArray(1000) { it.toShort() }
        val out = GreetingTrim.applyExclude(rec, 100 until 200)
        assertEquals(900, out.size)
        assertEquals(99.toShort(), out[99])
        assertEquals(200.toShort(), out[100]) // сэмпл после вырезанного диапазона сдвинулся на его место
    }

    @Test fun `разные частоты записи и эталона — ресемплится перед поиском`() {
        val greetHi = greeting() // построено на rate=16000
        val client = clientSpeech()
        val recording = concat(silence(500), greetHi, silence(300), client, silence(500))
        // Сам эталон отдаём как бы на другой (48000) частоте — findExcludeWindow должен сам
        // привести его к частоте записи (16000) через AudioConvert.resampleMono.
        val greetAt48k = com.davnozdu.autoresponder.call.AudioConvert.resampleMono(greetHi, rate, 48000)
        val window = GreetingTrim.findExcludeWindow(recording, rate, greetAt48k, 48000)
        assertNotNull("должно найти совпадение даже при разных частотах", window)
    }

    @Test fun `клиент бросил трубку во время приветствия — вся запись это обрывок с паузой в начале`() {
        val greet = greeting() // 4с полное приветствие
        // Запись обрывается на середине приветствия (звонок закончился раньше) — 1.8с, это
        // ровно НАЧАЛО того же самого сигнала greet, а не что-то другое. Плюс реалистичная
        // пауза перед началом (ответ + задержка play()) — findExcludeWindow-подобный offset=0
        // тут был бы неверен, isGreetingOnlyFragment обязан сам подобрать сдвиг.
        val partial = greet.copyOfRange(0, (1.8 * rate).toInt())
        val recording = concat(silence(200), partial)

        assertTrue("должен распознать обрывок приветствия по префиксу со сдвигом",
            GreetingTrim.isGreetingOnlyFragment(recording, rate, greet, rate))
    }

    @Test fun `короткое НЕ похожее на приветствие сообщение не считается его обрывком`() {
        val greet = greeting()
        // Короткий, но СВОЙ, не похожий на greet сигнал — реальное короткое сообщение клиента
        // ("алло?"), а не начало приветствия. Не должно ложно сработать как обрывок.
        val shortClient = buildSignal(listOf(300 to 0.9, 100 to 0.1, 300 to 0.9), seed = 777)
        val recording = concat(silence(200), shortClient)
        assertTrue("не похожее на приветствие короткое сообщение не должно считаться его обрывком",
            !GreetingTrim.isGreetingOnlyFragment(recording, rate, greet, rate))
    }

    @Test fun `запись длиннее эталона — не считается его обрывком (это обычный случай, не hangup)`() {
        val greet = greeting()
        val client = clientSpeech()
        val recording = concat(silence(500), greet, silence(300), client, silence(500))
        assertTrue(!GreetingTrim.isGreetingOnlyFragment(recording, rate, greet, rate))
    }

    @Test fun `isNegligible — короче порога значит нет сообщения, длиннее — есть`() {
        assertTrue(GreetingTrim.isNegligible(ShortArray((rate * 0.1).toInt()), rate))
        assertTrue(GreetingTrim.isNegligible(ShortArray(0), rate))
        assertTrue(!GreetingTrim.isNegligible(ShortArray(rate * 2), rate))
    }

    @Test fun `trimForTranscription — сквозной путь через сайдкар-файл на диске, обрыв на приветствии даёт пустой результат`() {
        val greet48k = buildSignalAtRate(48000, listOf(1500 to 0.8, 300 to 0.05, 1200 to 0.6, 1000 to 0.05), seed = 1)
        val tmpDir = java.nio.file.Files.createTempDirectory("greettrim_test_").toFile()
        try {
            val fakeRecordingPath = java.io.File(tmpDir, "rec.wav").absolutePath
            writeStereoSidecar(GreetingTrim.sidecarPath(fakeRecordingPath), greet48k)

            // Запись — обрыв на середине приветствия на "родной" частоте записи (16к), сайдкар
            // при этом честно лежит на диске в 48к/stereo, как в реальном пайплайне.
            val greetAt16k = com.davnozdu.autoresponder.call.AudioConvert.resampleMono(greet48k, 48000, rate)
            val partial = greetAt16k.copyOfRange(0, (1.8 * rate).toInt())
            val recording = concat(silence(200), partial)

            val trimmed = GreetingTrim.trimForTranscription(fakeRecordingPath, recording, rate)
            assertEquals(0, trimmed.size)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test fun `trimForTranscription — сквозной путь, обычное приветствие+сообщение обрезается, клиент цел`() {
        val greet48k = buildSignalAtRate(48000, listOf(1500 to 0.8, 300 to 0.05, 1200 to 0.6, 1000 to 0.05), seed = 1)
        val tmpDir = java.nio.file.Files.createTempDirectory("greettrim_test_").toFile()
        try {
            val fakeRecordingPath = java.io.File(tmpDir, "rec.wav").absolutePath
            writeStereoSidecar(GreetingTrim.sidecarPath(fakeRecordingPath), greet48k)

            val greetAt16k = com.davnozdu.autoresponder.call.AudioConvert.resampleMono(greet48k, 48000, rate)
            val client = clientSpeech()
            val recording = concat(silence(500), greetAt16k, silence(300), client, silence(500))

            val trimmed = GreetingTrim.trimForTranscription(fakeRecordingPath, recording, rate)
            assertTrue("должно укоротиться", trimmed.size < recording.size)
            assertTrue("речь клиента должна остаться в результате", indexOfSubarray(trimmed, client) >= 0)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test fun `trimForTranscription — сайдкара нет, возвращает запись без изменений`() {
        val recording = clientSpeech()
        val trimmed = GreetingTrim.trimForTranscription("/nonexistent/path/rec.wav", recording, rate)
        assertTrue(recording.contentEquals(trimmed))
    }

    private fun buildSignalAtRate(sr: Int, segmentsMs: List<Pair<Int, Double>>, seed: Int): ShortArray {
        val rnd = Random(seed)
        val totalSamples = segmentsMs.sumOf { it.first * sr / 1000 }
        val out = ShortArray(totalSamples)
        var pos = 0
        for ((durMs, amp) in segmentsMs) {
            val n = durMs * sr / 1000
            repeat(n) {
                if (pos < out.size) out[pos++] = ((rnd.nextDouble(-1.0, 1.0)) * amp * 20000).toInt().toShort()
            }
        }
        return out
    }

    /** Пишет [mono] как headerless raw PCM16 stereo (L=R) — формат всех Greeting.prepare*(). */
    private fun writeStereoSidecar(path: String, mono: ShortArray) {
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) { stereo[i * 2] = mono[i]; stereo[i * 2 + 1] = mono[i] }
        val bytes = ByteArray(stereo.size * 2)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in stereo) bb.putShort(s)
        java.io.File(path).writeBytes(bytes)
    }

    private fun indexOfSubarray(hay: ShortArray, needle: ShortArray): Int {
        if (needle.isEmpty() || needle.size > hay.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
