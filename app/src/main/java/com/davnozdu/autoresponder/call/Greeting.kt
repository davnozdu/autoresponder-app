package com.davnozdu.autoresponder.call

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Готовит приветствие как RAW PCM 48к/16/stereo для [AmBridge]/pal_inject: речь (TTS или свой
 * файл) + длинный сигнал «пишите после сигнала», одним файлом без щелчка между проигрываниями.
 * Запись разговора у нас не своя (её ведёт встроенная автозапись звонилки с момента ответа),
 * поэтому важно только то, что абонент слышит — а слышит он именно этот файл целиком.
 *
 * Два источника речи (настройка [Settings.amGreetingSource]):
 *   0 — TTS: синтез текста [Settings.amGreetingText] на языке клиента;
 *   1 — файл: пользователь загрузил свой аудиофайл ([Settings.amGreetingFile]).
 *
 * Результат кэшируется в `files/am/greeting.pcm` (уже с бипом); ключ кэша — рядом, чтобы не
 * пересинтезировать/не переконвертировать одно и то же на каждый звонок.
 */
object Greeting {
    private const val DIR = "am"
    private const val OUT = "greeting.pcm"
    private const val KEY = "greeting.key"

    // Параметры бипа — «долгий гудок, как у классического автоответчика».
    private const val SR = 48000
    private const val BEEP_GAP_MS = 400   // пауза после речи перед сигналом
    private const val BEEP_MS = 1300      // сам сигнал — долгий, ни с чем не спутать
    private const val BEEP_HZ = 1000.0
    private const val BEEP_AMP = 0.6

    // Таймауты на TTS: движок синтеза речи может не ответить (крашнулся/занят чем-то ещё).
    // Без таймаута корутина автоответчика зависла бы посреди звонка — микрофон остался бы
    // заглушённым, а отбоя не было бы никогда. Лучше остаться без приветствия, чем зависнуть.
    private const val TTS_INIT_TIMEOUT_MS = 4_000L
    private const val TTS_SYNTH_TIMEOUT_MS = 8_000L

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** @return путь к raw PCM приветствия (речь+бип) или null. [lang] — код языка клиента.
     *  [overrideText] — приветствие для конкретного клиента (напр. callPrompt из ЧС): если
     *  задано, озвучиваем именно его через TTS, минуя глобальный источник. */
    suspend fun prepare(ctx: Context, lang: String?, overrideText: String? = null): String? {
        val app = ctx.applicationContext
        val s = Settings(app)
        val out = File(dir(app), OUT)
        val keyFile = File(dir(app), KEY)

        val useFile = overrideText.isNullOrBlank() && s.amGreetingSource == 1 && s.amGreetingFile.isNotBlank()
        val key: String
        if (useFile) {
            val src = File(s.amGreetingFile)
            if (!src.exists()) { EventLog(app).add("AM приветствие: файла нет — ${s.amGreetingFile}"); return null }
            key = "file:${src.absolutePath}:${src.lastModified()}"
            if (out.exists() && keyFile.readTextSafe() == key) return out.absolutePath
            if (!AudioConvert.toRawPcm48kStereo(src.absolutePath, out.absolutePath)) {
                EventLog(app).add("AM приветствие: не сконвертировал файл ${src.name}"); return null
            }
        } else {
            val text = (overrideText?.takeIf { it.isNotBlank() }
                ?: s.amGreetingText).ifBlank { Settings.DEF_AM_GREETING }
            val lc = (lang ?: Locale.getDefault().language)
            key = "tts:$lc:${text.hashCode()}"
            if (out.exists() && keyFile.readTextSafe() == key) return out.absolutePath
            val wav = synthTts(app, text, lc) ?: run {
                EventLog(app).add("AM приветствие: TTS недоступен/не ответил вовремя"); return null
            }
            val ok = AudioConvert.toRawPcm48kStereo(wav.absolutePath, out.absolutePath)
            wav.delete()
            if (!ok) { EventLog(app).add("AM приветствие: не сконвертировал TTS"); return null }
        }
        // Дописываем бип ПОСЛЕ речи — это headerless PCM, поэтому обычный append байтов и
        // даёт правильную склейку без перекодирования и без щелчка на стыке (есть fade).
        try { FileOutputStream(out, true).use { it.write(beepTailPcm()) } }
        catch (e: Exception) { EventLog(app).add("AM приветствие: не добавил бип (${e.message})") }
        keyFile.writeText(key)
        return out.absolutePath
    }

    private fun beepTailPcm(): ByteArray {
        val fade = (SR * 0.008).toInt()
        val gapN = SR * BEEP_GAP_MS / 1000
        val beepN = SR * BEEP_MS / 1000
        val bb = ByteBuffer.allocate((gapN + beepN) * 4 /* stereo 16-бит = 4 байта/фрейм */)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(gapN) { bb.putShort(0); bb.putShort(0) }
        for (i in 0 until beepN) {
            val env = when {
                i < fade -> i.toDouble() / fade
                i > beepN - fade -> (beepN - i).toDouble() / fade
                else -> 1.0
            }
            val v = (BEEP_AMP * env * Math.sin(2 * Math.PI * BEEP_HZ * i / SR) * 32767)
                .toInt().coerceIn(-32768, 32767).toShort()
            bb.putShort(v); bb.putShort(v)
        }
        return bb.array()
    }

    private fun File.readTextSafe(): String = try { if (exists()) readText() else "" } catch (e: Exception) { "" }

    private fun localeFor(lang: String): Locale = when (lang.lowercase()) {
        "ru" -> Locale("ru"); "cs" -> Locale("cs"); "uk" -> Locale("uk")
        "en" -> Locale.ENGLISH; else -> Locale.getDefault()
    }

    private suspend fun synthTts(ctx: Context, text: String, lang: String): File? {
        val ready = CompletableDeferred<Boolean>()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        val inited = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!inited) { runCatching { tts.shutdown() }; return null }
        try {
            tts.language = localeFor(lang)
            val wav = File(ctx.cacheDir, "am_tts.wav")
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.complete(true) }
                @Deprecated("deprecated") override fun onError(id: String?) { done.complete(false) }
                override fun onError(id: String?, code: Int) { done.complete(false) }
            })
            val res = tts.synthesizeToFile(text, Bundle(), wav, "greet")
            if (res != TextToSpeech.SUCCESS) done.complete(false)
            val ok = withTimeoutOrNull(TTS_SYNTH_TIMEOUT_MS) { done.await() } ?: false
            return if (ok && wav.exists() && wav.length() > 0) wav else null
        } finally {
            runCatching { tts.shutdown() }
        }
    }
}
