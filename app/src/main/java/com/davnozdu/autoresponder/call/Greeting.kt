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
 * Результат кэшируется в `files/am/greeting-<хэш ключа>.pcm` (уже с бипом) — ОТДЕЛЬНЫЙ файл на
 * каждую пару (текст, язык)/(файл, mtime), а не один общий слот на все приветствия сразу. Раньше
 * общее приветствие и ЧС-промпт КАЖДОГО контакта делили один и тот же `greeting.pcm`: смена
 * промпта у одного контакта пересинтезировала файл под его ключ, но если следующий звонок (от
 * другого контакта с другим текстом) заставал недописанный/устаревший файл в этом единственном
 * слоте — ключ не совпадал, но само разделение на слот-на-контакт отсутствовало в принципе,
 * что и приводило к путанице между приветствиями разных контактов. Имя файла = хэш ключа, так
 * что коллизии исключены самой конструкцией — сверять ключ отдельно больше не нужно.
 */
object Greeting {
    private const val DIR = "am"
    // Меняем при правках синтеза/конвертации (напр. фикс скорости TTS), чтобы старый
    // закэшированный файл не пережил обновление приложения — ключ ниже строится от текста/
    // файла, и без версии сам текст не меняется, значит кэш остался бы прежним.
    private const val SYNTH_VER = "v3"
    // Сколько разных приветствий держим в кэше одновременно (общее + по контакту ЧС) — с
    // запасом под редактирование текстов; лишние (самые старые) удаляются при каждом prepare().
    private const val KEEP_CACHED = 24

    // Параметры бипа — «долгий гудок, как у классического автоответчика».
    private const val SR = 48000
    private const val BEEP_GAP_MS = 800   // пауза между бипами (была 400 — пользователь попросил вдвое больше)
    private const val BEEP_MS = 1300      // сам сигнал — долгий, ни с чем не спутать
    private const val BEEP_HZ = 1000.0
    private const val BEEP_AMP = 0.6

    // Таймауты на TTS: движок синтеза речи может не ответить (крашнулся/занят чем-то ещё).
    // Без таймаута корутина автоответчика зависла бы посреди звонка — микрофон остался бы
    // заглушённым, а отбоя не было бы никогда. Лучше остаться без приветствия, чем зависнуть.
    private const val TTS_INIT_TIMEOUT_MS = 4_000L
    private const val TTS_SYNTH_TIMEOUT_MS = 8_000L

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** Имя файла кэша = хэш ключа: разные (текст,язык)/(файл,mtime) физически не могут
     *  попасть в один слот, поэтому сверять ключ отдельным .key-файлом больше не нужно. */
    private fun cacheFile(app: Context, key: String): File =
        File(dir(app), "greeting-${key.hashCode().toUInt().toString(16)}.pcm")

    private fun cleanupOldCaches(app: Context, exceptFile: File) {
        // Легаси-имя из версии, где все приветствия делили один слот — больше не читается
        // никем (SYNTH_VER сменился), просто мёртвый груз.
        File(dir(app), "greeting.pcm").delete(); File(dir(app), "greeting.key").delete()
        val files = dir(app).listFiles { f -> f.name.startsWith("greeting-") && f.name.endsWith(".pcm") }
            ?: return
        if (files.size <= KEEP_CACHED) return
        files.filter { it != exceptFile }.sortedBy { it.lastModified() }
            .dropLast((KEEP_CACHED - 1).coerceAtLeast(0)).forEach { it.delete() }
    }

    /** @return путь к raw PCM приветствия (речь+бип) или null. [lang] — явный язык TTS (код
     *  "cs"/"en"/"ru"/"uk" или null = язык устройства, ненадёжно — см. [localeFor]).
     *  [overrideText] — приветствие для конкретного клиента (напр. callPrompt из ЧС): если
     *  задано, озвучиваем именно его через TTS, минуя глобальный источник. */
    suspend fun prepare(ctx: Context, lang: String?, overrideText: String? = null): String? {
        val app = ctx.applicationContext
        val s = Settings(app)

        val useFile = overrideText.isNullOrBlank() && s.amGreetingSource == 1 && s.amGreetingFile.isNotBlank()
        val text: String; val lc: String; val src: File?
        val key: String
        if (useFile) {
            src = File(s.amGreetingFile)
            if (!src.exists()) { EventLog(app).add("AM приветствие: файла нет — ${s.amGreetingFile}"); return null }
            text = ""; lc = ""
            key = "$SYNTH_VER:file:${src.absolutePath}:${src.lastModified()}"
        } else {
            src = null
            text = (overrideText?.takeIf { it.isNotBlank() }
                ?: s.amGreetingText).ifBlank { Settings.DEF_AM_GREETING }
            lc = (lang?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language)
            key = "$SYNTH_VER:tts:$lc:${text.hashCode()}"
        }
        val out = cacheFile(app, key)
        if (out.exists() && out.length() > 0) return out.absolutePath

        if (useFile) {
            if (!AudioConvert.toRawPcm48kStereo(src!!.absolutePath, out.absolutePath)) {
                EventLog(app).add("AM приветствие: не сконвертировал файл ${src.name}"); return null
            }
        } else {
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
        cleanupOldCaches(app, out)
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

    /** Один и тот же блок бипа, повторённый столько раз, чтобы покрыть [maxTotalMs] —
     *  для зуммера скрининга: абонент слышит повторяющийся сигнал, пока владелец не решит
     *  («Принять»/«Отклонить») или пока не истечёт [Settings.amMaxMessageSec]. Минимум один
     *  повтор всегда, даже при [maxTotalMs] <= 0 (испорченная настройка не должна давать
     *  пустой/битый PCM). */
    fun repeatingBeepPcm(maxTotalMs: Int): ByteArray {
        val unit = beepTailPcm()
        val unitMs = BEEP_GAP_MS + BEEP_MS
        val repeats = (maxTotalMs / unitMs).coerceAtLeast(1)
        val out = ByteArray(unit.size * repeats)
        for (i in 0 until repeats) unit.copyInto(out, i * unit.size)
        return out
    }

    /** Приветствие скрининга: как [prepare], но источник — своя тройка настроек на [lang]
     *  ("cs"/"ru"/"en") вместо общих `amGreeting*`, и после речи повторяющийся зуммер
     *  ([repeatingBeepPcm]) вместо одного бипа — абонент ждёт под сигнал, пока владелец не
     *  решит через карточку. Обрывается штатным [AmBridge.stop], отдельного протокола не
     *  требуется — файл просто длинный (речь + зуммер на всю [maxTotalMs]). */
    /** [slot] выбирает, какую из трёх коробок читать ("ru"/"en"/иначе cs) — НЕ обязательно
     *  язык TTS: у каждой коробки свой явный язык синтеза (`screeningGreetingLang*`),
     *  независимый от того, в какой коробке лежит текст (пользователь попросил явный чип,
     *  а не неявную привязку языка синтеза к позиции коробки). */
    suspend fun prepareScreening(ctx: Context, slot: String, maxTotalMs: Int): String? {
        val app = ctx.applicationContext
        val s = Settings(app)
        val source: Int; val file: String; val text: String; val ttsLang: String; val defText: String
        when (slot) {
            "ru" -> { source = s.screeningGreetingSourceRu; file = s.screeningGreetingFileRu; text = s.screeningGreetingTextRu
                      ttsLang = s.screeningGreetingLangRu; defText = Settings.DEF_SCREEN_GREETING_RU }
            "en" -> { source = s.screeningGreetingSourceEn; file = s.screeningGreetingFileEn; text = s.screeningGreetingTextEn
                      ttsLang = s.screeningGreetingLangEn; defText = Settings.DEF_SCREEN_GREETING_EN }
            else -> { source = s.screeningGreetingSourceCs; file = s.screeningGreetingFileCs; text = s.screeningGreetingTextCs
                      ttsLang = s.screeningGreetingLangCs; defText = Settings.DEF_SCREEN_GREETING_CS }
        }
        val useFile = source == 1 && file.isNotBlank()
        val src: File?
        val key: String
        if (useFile) {
            src = File(file)
            if (!src.exists()) { EventLog(app).add("AM скрининг: файла нет — $file"); return null }
            key = "$SYNTH_VER:screen:file:$slot:${src.absolutePath}:${src.lastModified()}:$maxTotalMs"
        } else {
            src = null
            key = "$SYNTH_VER:screen:tts:$slot:$ttsLang:${text.hashCode()}:$maxTotalMs"
        }
        val out = cacheFile(app, key)
        if (out.exists() && out.length() > 0) return out.absolutePath

        if (useFile) {
            if (!AudioConvert.toRawPcm48kStereo(src!!.absolutePath, out.absolutePath)) {
                EventLog(app).add("AM скрининг: не сконвертировал файл ${src.name}"); return null
            }
        } else {
            val wav = synthTts(app, text.ifBlank { defText }, ttsLang) ?: run {
                EventLog(app).add("AM скрининг: TTS недоступен/не ответил вовремя"); return null
            }
            val ok = AudioConvert.toRawPcm48kStereo(wav.absolutePath, out.absolutePath)
            wav.delete()
            if (!ok) { EventLog(app).add("AM скрининг: не сконвертировал TTS"); return null }
        }
        try { FileOutputStream(out, true).use { it.write(repeatingBeepPcm(maxTotalMs)) } }
        catch (e: Exception) { EventLog(app).add("AM скрининг: не добавил зуммер (${e.message})") }
        cleanupOldCaches(app, out)
        return out.absolutePath
    }

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
            // Раньше результат setLanguage() не проверялся (просто `tts.language = ...`) — если
            // у движка нет голоса для нужного языка (LANG_MISSING_DATA/LANG_NOT_SUPPORTED), он
            // МОЛЧА озвучивает текущим/движковым голосом (обычно английским), и по звуку это
            // неотличимо от «язык проигнорирован». Логируем явно, чтобы это было видно сразу,
            // а не гадать по тому, что «приветствие вышло на английском».
            val langRes = tts.setLanguage(localeFor(lang))
            if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                EventLog(ctx).add("AM приветствие: нет голоса TTS для языка «$lang» (код $langRes) — " +
                    "звучит текущим голосом движка, не запрошенным")
            }
            // Без явного вызова движок берёт скорость/высоту тона из системных Специальных
            // возможностей владельца (Google TTS так и делает) — а это звучит для ПОСТОРОННЕГО
            // звонящего, и личная настройка владельца («побыстрее для чтения экрана») тут
            // неуместна. Живой тест это и поймал: 18 слов уложились в ~2.5с — почти в 3 раза
            // быстрее нормальной речи. Фиксируем нормальный темп независимо от системных настроек.
            tts.setSpeechRate(1.0f)
            tts.setPitch(1.0f)
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
