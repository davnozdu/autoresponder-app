package com.davnozdu.autoresponder.call

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Locale

/**
 * Готовит приветствие как RAW PCM 48к/16/stereo для [AmBridge]/pal_inject.
 *
 * Два источника (настройка [Settings.amGreetingSource]):
 *   0 — TTS: синтез текста [Settings.amGreetingText] на языке клиента;
 *   1 — файл: пользователь загрузил свой аудиофайл ([Settings.amGreetingFile]).
 *
 * Результат кэшируется в `files/am/greeting.pcm`; ключ кэша хранится рядом, чтобы не
 * пересинтезировать один и тот же текст/файл на каждый звонок.
 */
object Greeting {
    private const val DIR = "am"
    private const val OUT = "greeting.pcm"
    private const val KEY = "greeting.key"

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** @return путь к raw PCM приветствия или null. [lang] — код языка клиента (ru/cs/en/uk).
     *  [overrideText] — приветствие для конкретного клиента (напр. callPrompt из ЧС): если задано,
     *  озвучиваем именно его через TTS, минуя глобальный источник. */
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
                EventLog(app).add("AM приветствие: TTS недоступен"); return null
            }
            val ok = AudioConvert.toRawPcm48kStereo(wav.absolutePath, out.absolutePath)
            wav.delete()
            if (!ok) { EventLog(app).add("AM приветствие: не сконвертировал TTS"); return null }
        }
        keyFile.writeText(key)
        return out.absolutePath
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
        if (!ready.await()) { tts.shutdown(); return null }
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
            if (res != TextToSpeech.SUCCESS) { done.complete(false) }
            val ok = done.await()
            return if (ok && wav.exists() && wav.length() > 0) wav else null
        } finally {
            tts.shutdown()
        }
    }
}
