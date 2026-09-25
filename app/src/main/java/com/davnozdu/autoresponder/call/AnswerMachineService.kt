package com.davnozdu.autoresponder.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.store.HistoryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Голосовой автоответчик для одного звонка. Приложение НЕ становится дефолтной звонилкой —
 * отвечаем через acceptRingingCall (право ANSWER_PHONE_CALLS от модуля), а встроенная
 * автозапись OxygenOS пишет разговор сама. Здесь: ответить → заглушить (микрофон + вывод
 * владельцу) → проиграть приветствие в линию (через root-демон) → подождать сообщение →
 * отбой → скопировать запись → строка в журнал автоответчика.
 */
class AnswerMachineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var idle = false
    @Volatile private var offhook = false
    @Volatile private var accepted = false
    @Volatile private var declined = false
    @Volatile private var transferred = false
    private var watcher: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val number = intent?.getStringExtra(EX_NUMBER)
        val name = intent?.getStringExtra(EX_NAME)
        val reason = intent?.getStringExtra(EX_REASON) ?: "closed"
        val lang = intent?.getStringExtra(EX_LANG)
        val text = intent?.getStringExtra(EX_TEXT)
        val testSec = intent?.getIntExtra(EX_TEST_SEC, 0) ?: 0
        // Второй звонок может стартовать сервис, пока первый ещё не завершился (call
        // waiting) — onStartCommand у Service вызывается на том же экземпляре повторно.
        // Весь протокол демона (req/resp, PID-файлы, единственный rec.wav) на одну сессию,
        // не на звонок, поэтому второй параллельный сценарий ломал бы состояние первого.
        // onStartCommand всегда на главном потоке — проверка-и-установка без гонки.
        if (busy) {
            EventLog(applicationContext).add("AM: параллельный вызов ${number ?: "?"} пропущен — уже идёт другая сессия")
            stopSelfSafe()
            return START_NOT_STICKY
        }
        busy = true
        active = this
        scope.launch {
            try {
                when (reason) {
                    "test" -> runTestFlow(testSec.coerceIn(3, 120))
                    "screening" -> runScreeningFlow(number, name)
                    else -> runFlow(number, name, reason, lang, text)
                }
            }
            catch (e: Exception) { EventLog(applicationContext).add("AM: сбой (${e.message})") }
            finally { busy = false; stopSelfSafe() }
        }
        return START_NOT_STICKY
    }

    /** Проверка блокировки экрана/тача БЕЗ реального звонка — тот же foreground-сервис,
     *  что и настоящий вызов, значит те же исключения из заморозки процесса OxygenOS.
     *  Мьют/громкость сюда намеренно не входят: без активного звонка STREAM_VOICE_CALL
     *  ничего не значит. */
    private suspend fun runTestFlow(seconds: Int) {
        val app = applicationContext
        EventLog(app).add("AM ТЕСТ: старт на ${seconds}с (blockon: подсветка+тач)")
        AmBridge.blockOn(app, android.os.Process.myPid())
        AmBlockOverlay.show(app)
        // Смоук-тест команды play (демон ответит err nofile на несуществующий путь — это
        // ожидаемо и не мешает проверке самой доставки команды/ack).
        AmBridge.play(app, "/data/local/tmp/am_race_test_nofile.pcm", 1)
        try {
            // Подсветку теперь держит локальный цикл демона (redim_loop.sh, запускается
            // самим blockon) — просто ждём истечения теста.
            delay(seconds * 1000L)
        } finally {
            AmBlockOverlay.hide(app)
            AmBridge.blockOff(app)
            EventLog(app).add("AM ТЕСТ: завершено")
        }
    }

    private suspend fun runFlow(number: String?, nameIn: String?, reason: String,
                                lang: String?, greetingText: String?) {
        val app = applicationContext
        val s = Settings(app)
        val db = HistoryDb.get(app)
        val start = System.currentTimeMillis()
        val name = nameIn ?: contactName(app, number)
        EventLog(app).add("AM: старт ${number ?: "?"} (${reason})")

        registerWatcher(app)
        try {
            // 1) Ответить.
            answerCall(app)
            if (!offhook) {
                // Клиент положил трубку раньше, чем мы ответили, или acceptRingingCall не
                // сработал (гонка/особенности прошивки). Строку в журнал НЕ создаём — иначе
                // список записей засорялся бы пустышками без файла и без смысла.
                EventLog(app).add("AM: не ответили вовремя ${number ?: "?"} — пропуск")
                return
            }

            // Запись создаём только теперь, когда звонок реально принят — с этого момента
            // встроенная автозапись звонилки тоже уже пишет (она стартует по offhook), так что
            // greeting+бип+сообщение клиента попадут в один файл с самого начала разговора.
            val recId = db.amRecInsert(number, name, start, 0, null, reason)

            // Железная блокировка: подсветка в 0 через sysfs + тачскрин выключен на уровне
            // ядра (портировано из vr-usb-monitor, проверено на этом телефоне) — не зависит
            // от Keyguard/DisplayManager, никакой борьбы за то, чьё окно поверх. Плюс наша
            // накладка как резервный слой (на случай, если sysfs-путь вдруг не нашёлся).
            AmBridge.blockOn(app, android.os.Process.myPid())
            AmBlockOverlay.show(app)

            // Своя запись (pal_record, incall-record тап) — параллельно штатной с самого
            // начала звонка, не дожидаясь проверки: штатный рекордер OxygenOS иногда (~1
            // звонок из 4) не подхватывается вовсе, без ошибки и без файла. Буфер лежит в
            // ОЗУ на стороне демона (tmpfs моста мессенджеров) — на флеш ничего не пишется,
            // пока после звонка не выяснится, что штатная запись не появилась (см. шаг 7).
            val safeNum = (number ?: "unknown").replace(Regex("[^+0-9]"), "")
            val ownRecPath = "/sdcard/AutoResponder/recordings/" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date(start)) +
                "_${safeNum}_own.wav"
            val maxSec = s.amMaxMessageSec.coerceIn(5, 300)
            AmBridge.recStart(app, maxSec)

            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 2) Заглушить: микрофон (абонент не слышит комнату) и, в тихом режиме, вывод к владельцу.
            // ADJUST_MUTE/ADJUST_UNMUTE вместо явного индекса громкости: индекс у STREAM_VOICE_CALL
            // свой для каждого маршрута (earpiece/speaker/bt_sco/...), а API get/setStreamVolume
            // без указания устройства бьёт по ТЕКУЩЕМУ активному — если маршрут сменится между
            // «заглушить» и «вернуть» (гарнитура, PAL-переключения), восстановится не тот
            // маршрут, а исходный так и останется на нуле (живой баг: bt_sco у владельца осел
            // на минимуме после серии тестовых звонков). Флаг мьюта не привязан к устройству —
            // снимается тем же ADJUST_UNMUTE независимо от того, куда успела уехать активная
            // маршрутизация, и безопасен даже если стрим не был заглушен вовсе.
            runCatching { am.isMicrophoneMute = true }
            if (s.amSilentToOwner) {
                runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
                AmBridge.muteOut(app, true)
            }

            // 3) Приветствие + длинный бип в линию (один файл — без щелчка между проигрываниями).
            // Доп. пояс безопасности поверх таймаутов внутри Greeting: звонок не должен
            // зависнуть целиком, если где-то в цепочке TTS/конвертации что-то пойдёт не так.
            val greet = kotlinx.coroutines.withTimeoutOrNull(15_000L) { Greeting.prepare(app, lang, greetingText) }
            if (greet != null) {
                AmBridge.play(app, greet, 1)
                // Штатная автозапись звонилки иногда (на глаз ~1 звонок из 4) не подхватывает
                // именно тихо-принятые звонки — без видимой причины, файла не появляется вовсе
                // (см. переписку/журнал: не баг связывания, рекордер просто не стартовал). Раз
                // уж своей записи у нас нет, ловим это СРАЗУ, а не постфактум по пустому файлу:
                // пока звучит приветствие+бип есть пара секунд до того, как заговорит клиент —
                // если за это время в системе не появится активная запись с голосового источника,
                // помечаем звонок в журнале сразу, не дожидаясь конца разговора.
                scope.launch { checkOemRecorderStarted(app) }
            } else EventLog(app).add("AM: приветствие не готово — молчим")

            // 4) Держим линию под сообщение клиента, пока не положит трубку или не выйдет таймаут.
            // Живой тест поймал: пока экран горел — тихо, а когда погас (обычный таймаут
            // экрана) — владелец начал слышать абонента. Что-то (InCallUI/OxygenOS) сбрасывает
            // громкость/мьют при смене состояния экрана. Вместо попытки понять точную причину —
            // просто переустанавливаем заглушку на каждом тике ожидания, а не один раз в начале.
            waitIdleKeepingSilent(maxSec * 1000L, app, am, s.amSilentToOwner)

            // 5) Отбой, если ещё не завершён.
            if (!idle) endCall(app)

            // 6) Вернуть звук, подсветку, тач и убрать накладку — звонок уже завершён/
            // завершается, случайно сбросить больше нечего.
            AmBridge.stop(app)
            AmBridge.recStop(app)
            // recStop() возвращается по своему ack-таймауту (≤400мс) — daemon-сторона может
            // дописывать WAV-заголовок ещё какое-то время после этого (ждёт выхода pal_record
            // до 15с как подстраховку). Небольшая пауза здесь дешевле, чем читать наш файл
            // с нулевым/неполным заголовком чуть ниже.
            delay(500)
            if (s.amSilentToOwner) AmBridge.muteOut(app, false)
            AmBridge.blockOff(app)
            AmBlockOverlay.hide(app)

            // 7) Дождаться простоя и подобрать запись звонилки → отдельная папка + журнал.
            // Своя (pal_record) лежит в ОЗУ-буфере демона, ещё не на диске — recSave() перенесёт
            // её на диск, только если штатная не нашлась; иначе recDiscard() просто сотрёт буфер,
            // не трогая флеш вовсе.
            waitIdleOr(5_000L)
            val link = RecordingLinker.linkLatest(app, number, start)
            if (link != null) {
                db.amRecSetFile(recId, link.first, link.second)
                AmBridge.recDiscard(app)
            } else {
                AmBridge.recSave(app, ownRecPath)
                val ownFile = java.io.File(ownRecPath)
                if (ownFile.exists() && ownFile.length() > 44) {
                    val dur = RecordingLinker.durationMs(ownFile.absolutePath)
                    db.amRecSetFile(recId, ownFile.absolutePath, dur)
                    EventLog(app).add("AM запись: штатный рекордер не сработал — оставил свою (${dur/1000}s)")
                } else {
                    db.amRecSetFile(recId, "", System.currentTimeMillis() - start)
                }
            }

            EventLog(app).add("AM: завершено ${number ?: "?"}")
        } finally {
            // Гарантированно снимаем ресивер, накладку, железную блокировку И заглушку звука
            // даже при раннем return/исключении — залипший тёмный нетрогаемый экран или
            // навсегда заглушенный голос звонка были бы худшим возможным отказом. Root-сторож
            // в демоне страхует только смерть ПРОЦЕССА; исключение внутри ещё живого процесса
            // он не увидит — снимаем сами. ADJUST_UNMUTE безопасен, даже если мьюта не было.
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.isMicrophoneMute = false }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
            unregisterWatcher(app)
            AmBlockOverlay.hide(app)
            AmBridge.blockOff(app)
            AmBridge.recStop(app)
        }
    }

    /** Интерактивный скрининг: отвечаем, играем приветствие+зуммер, абонент ждёт, владелец
     *  решает через карточку CallerOverlay. В отличие от [runFlow] — экран НЕ гасится и НЕ
     *  блокируется (никакого AmBlockOverlay/AmBridge.blockOn): это видимый, а не тихий режим.
     *  «Принять» — снять мьют и отдать линию владельцу (звонок не пересоединяется, он был
     *  активен всё это время); «Отклонить» — обычный отбой. */
    private suspend fun runScreeningFlow(number: String?, nameIn: String?) {
        val app = applicationContext
        val s = Settings(app)
        val db = HistoryDb.get(app)
        val start = System.currentTimeMillis()
        val name = nameIn ?: contactName(app, number)
        // Тот же вид номера, что использует CallerCardNotifier.onIncoming (PhoneMask.normalize)
        // — иначе CallerOverlay.show() увидел бы РАЗНЫЕ ключи для одного и того же звонка
        // («+420…» вместо «00420…») и стёр бы кнопки, приняв его за другой номер.
        val normNumber = com.davnozdu.autoresponder.rules.PhoneMask.normalize(number) ?: (number ?: "")
        EventLog(app).add("AM: старт ${number ?: "?"} (screening)")
        accepted = false; declined = false; transferred = false

        registerWatcher(app)
        try {
            answerCall(app)
            if (!offhook) {
                EventLog(app).add("AM: не ответили вовремя ${number ?: "?"} — пропуск")
                return
            }
            val safeNum = (number ?: "unknown").replace(Regex("[^+0-9]"), "")
            val ownRecPath = "/sdcard/AutoResponder/recordings/" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date(start)) +
                "_${safeNum}_own.wav"
            // Хвост ожидания (зуммер/hold-музыка) и собственный буфер записи должны покрывать
            // весь screeningWaitSec, а не отдельный amMaxMessageSec (тот — таймаут ДРУГОГО,
            // тихого сценария runFlow) — иначе после конца короткого хвоста абонент слышит
            // мёртвую тишину до самого автопереброса, а recStart останавливает буфер раньше,
            // чем истекает ожидание. Найдено финальным ревью ветки.
            val waitSec = s.screeningWaitSec.coerceIn(5, 300)
            AmBridge.recStart(app, waitSec)

            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.isMicrophoneMute = true }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }

            // Карточка — СРАЗУ, без ожидания CRM (lookup=null): владелец должен мочь нажать
            // «Ответить» немедленно, а не после сетевого похода за CRM-данными.
            com.davnozdu.autoresponder.notif.CallerOverlay.showScreening(app, normNumber, null,
                onAccept = { screeningAccept() }, onDecline = { screeningDecline() },
                onTransfer = { screeningTransfer() })
            // CRM донасыщается ПАРАЛЛЕЛЬНО, не блокируя карточку/кнопки — в фоновой корутине,
            // тем же CallerOverlay.show() (кнопки сохранятся по normNumber, см.
            // CallerOverlay.acceptDeclineFor), а НЕ через CallerCardNotifier: та зовёт
            // NotificationCompat — ОТДЕЛЬНОЕ Android-уведомление, своя поверхность и z-order,
            // и на экране накладывалась на карточку скрининга вместо того, чтобы уйти под неё
            // (живой тест: две карточки одна поверх другой). Для скрининга
            // CallScreeningServiceImpl вообще не зовёт CallerCardNotifier — см. likelyScreening
            // там же — единственное окно на весь сценарий, весь порядок элементов внутри него
            // под нашим контролем.
            scope.launch {
                val lookup = runCatching {
                    com.davnozdu.autoresponder.crm.CrmFlow.lookup(app, listOf(normNumber))
                }.getOrNull()
                if (lookup != null) {
                    com.davnozdu.autoresponder.notif.CallerOverlay.show(app, normNumber, lookup)
                }
            }

            val greet = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                Greeting.prepareScreening(app, s.screeningDefaultLang, waitSec * 1000)
            }
            // Пока готовился TTS (до 15с), владелец мог уже нажать «Ответить»/«Отклонить» —
            // тогда играть приветствие незачем, оно тут же оборвётся.
            if (greet != null && !accepted && !declined) AmBridge.play(app, greet, 1)
            else if (greet == null) EventLog(app).add("AM: приветствие для скрининга не готово — молчим")

            waitScreeningDecision(waitSec * 1000L, am)

            if (accepted) {
                AmBridge.stop(app)
                runCatching { am.isMicrophoneMute = false }
                runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
                EventLog(app).add("AM: скрининг — владелец принял ${number ?: "?"}")
                com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)
                // Дальше это обычный разговор — штатная запись звонилки (если включена)
                // продолжает сама, как у любого вручную принятого звонка. Свой буфер
                // (pal_record) тут не нужен — отбрасываем, а не сохраняем: сохранённый файл
                // содержал бы только приветствие+зуммер до момента ответа, не сам разговор, и
                // создавать под него запись в журнале (которую нечем будет проиграть) незачем.
                AmBridge.recStop(app)
                AmBridge.recDiscard(app)
                return
            }

            if (transferred) {
                // Буфер, который писался во время ожидания карточки, — не нужен, стираем:
                // клиент должен услышать voicemail-приветствие и знать, что теперь пишется
                // именно его сообщение, а не молчаливое продолжение прежней записи.
                AmBridge.recStop(app)
                AmBridge.recDiscard(app)
                AmBridge.stop(app)
                com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)

                val vmGreet = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    Greeting.prepareVoicemail(app, s.screeningDefaultLang)
                }
                if (vmGreet != null) AmBridge.play(app, vmGreet, 1)
                else EventLog(app).add("AM: voicemail-приветствие не готово — молчим")

                val vmMaxSec = s.voicemailMaxSec.coerceIn(5, 300)
                AmBridge.recStart(app, vmMaxSec)          // запись С НУЛЯ, новый файл
                // waitIdleOr (однократная установка мьюта) тут не годится — то же самое, что
                // и в step 2 runFlow: что-то на стороне системы (смена состояния экрана и
                // т.п.) сбрасывает мьют/громкость в середине ожидания, и без переустановки на
                // каждом тике владелец начинает слышать голосовую почту клиента, а его комната
                // — попадать абоненту в трубку и в саму запись. Найдено финальным ревью ветки.
                waitIdleKeepingSilent(vmMaxSec * 1000L, app, am, s.amSilentToOwner)
                if (!idle) endCall(app)
                AmBridge.stop(app)
                AmBridge.recStop(app)
                delay(500)                                 // дать демону дописать WAV-заголовок

                // Своя (передёрнутая) запись — ВСЕГДА главная для этого события.
                val recId = db.amRecInsert(number, name, start, 0, null, "voicemail")
                AmBridge.recSave(app, ownRecPath)
                val ownFile = java.io.File(ownRecPath)
                if (ownFile.exists() && ownFile.length() > 44) {
                    db.amRecSetFile(recId, ownFile.absolutePath, RecordingLinker.durationMs(ownFile.absolutePath))
                } else {
                    db.amRecSetFile(recId, "", System.currentTimeMillis() - start)
                }
                // OEM-запись (если штатный рекордер поймал звонок с начала) сохраняем ОТДЕЛЬНОЙ,
                // НЕ главной записью — своим recId, не переиспользуя recId выше.
                val oemLink = RecordingLinker.linkLatest(app, number, start)
                if (oemLink != null) {
                    // heard=true сразу: это ДОПОЛНИТЕЛЬНАЯ копия того же звонка (спека, §3) —
                    // не должна ни попадать в счётчик "новых" записей, ни выглядеть как ещё
                    // одно непрослушанное сообщение рядом с главной "voicemail"-записью.
                    // Найдено финальным ревью ветки.
                    val fullId = db.amRecInsert(number, name, start, 0, null, "voicemail_full", heard = true)
                    db.amRecSetFile(fullId, oemLink.first, oemLink.second)
                }
                EventLog(app).add("AM: скрининг — переброшено на автоответчик ${number ?: "?"}")
                return
            }

            if (declined || !idle) endCall(app)
            AmBridge.stop(app)
            AmBridge.recStop(app)
            delay(500)
            com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)

            // Запись в журнал — только теперь, когда точно знаем, что звонок НЕ был принят
            // владельцем (Отклонить/таймаут/абонент положил трубку сам): у принятых звонков
            // строка в истории не нужна (см. ветку accepted выше).
            val recId = db.amRecInsert(number, name, start, 0, null, "screening")
            waitIdleOr(5_000L)
            val link = RecordingLinker.linkLatest(app, number, start)
            if (link != null) {
                db.amRecSetFile(recId, link.first, link.second)
                AmBridge.recDiscard(app)
            } else {
                AmBridge.recSave(app, ownRecPath)
                val ownFile = java.io.File(ownRecPath)
                if (ownFile.exists() && ownFile.length() > 44) {
                    val dur = RecordingLinker.durationMs(ownFile.absolutePath)
                    db.amRecSetFile(recId, ownFile.absolutePath, dur)
                    EventLog(app).add("AM запись: штатный рекордер не сработал — оставил свою (${dur/1000}s)")
                } else {
                    db.amRecSetFile(recId, "", System.currentTimeMillis() - start)
                }
            }
            EventLog(app).add("AM: завершено ${number ?: "?"}")
        } finally {
            // Как и в runFlow — гарантированно снимаем мьют/запись даже при исключении
            // между recStart и explicit recStop в обеих ветках выше (accept/decline/idle).
            unregisterWatcher(app)
            com.davnozdu.autoresponder.notif.CallerOverlay.hide(app)
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.isMicrophoneMute = false }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
            AmBridge.stop(app)
            AmBridge.recStop(app)
        }
    }

    private suspend fun waitScreeningDecision(budgetMs: Long, am: AudioManager) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !idle && !accepted && !declined && !transferred) {
            runCatching { am.isMicrophoneMute = true }
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
            delay(300)
        }
        // Время вышло, а владелец так и не отреагировал — автоматический переброс на
        // автоответчик, равнозначно нажатию кнопки (см. спеку: "5 минут" — это именно этот
        // таймаут). !idle: абонент ещё на линии, есть кого перебрасывать. Карточку скрываем
        // ТУТ ЖЕ, а не после IPC-обмена в вызывающем коде — иначе секунду-две кнопки
        // Ответить/Отклонить остаются видимыми и нажимаемыми после того, как решение уже
        // принято автоматически, и владелец либо промахивается мимо уже неактуальной кнопки,
        // либо думает, что ответил, хотя линия уже уходит на голосовую почту. Найдено
        // финальным ревью ветки.
        if (System.currentTimeMillis() >= deadline && !idle && !accepted && !declined) {
            transferred = true
            com.davnozdu.autoresponder.notif.CallerOverlay.hide(applicationContext)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun answerCall(ctx: Context) {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val deadline = System.currentTimeMillis() + 6_000L
        // Тоже выходим по idle: если клиент положил трубку раньше, чем мы успели ответить,
        // не стоит долбить acceptRingingCall() ещё несколько секунд впустую.
        while (System.currentTimeMillis() < deadline && !offhook && !idle) {
            try { tm.acceptRingingCall() } catch (_: Exception) {}
            // Резервный путь, независимый от broadcast: Android документирует, что системный
            // OFFHOOK-broadcast от привилегированного отправителя может не дойти до
            // RECEIVER_NOT_EXPORTED-ресивера на части OEM-прошивок (см. registerWatcher).
            // TelecomManager.isInCall() ничего не ждёт от системы вещания — если он уже
            // видит активный вызов, считаем «ответили», не дожидаясь broadcast вовсе.
            if (runCatching { tm.isInCall() }.getOrDefault(false)) { offhook = true; break }
            delay(400)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun endCall(ctx: Context) {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.endCall()
            }
        } catch (_: Exception) {}
    }

    private suspend fun waitIdleOr(budgetMs: Long) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !idle) delay(300)
    }

    /** Как [waitIdleOr], но на каждом тике переустанавливает мьют/громкость — что-то сбрасывает
     *  их при смене состояния экрана, разовой установки недостаточно. Подсветку раньше
     *  переустанавливал тот же тик через [AmBridge.redim] (IPC-файл на каждый цикл — до ~33
     *  файловых round-trip'ов в секунду на весь звонок); теперь это отдельный локальный цикл
     *  демона (redim_loop.sh, запускается самим blockon — см. answermachine.sh), без всякого
     *  IPC отсюда. Частоту тика (30мс, а не 200) трогать нельзя: DisplayManager перебивает
     *  подсветку через пару секунд, а на реальном звонке проверялось, что более редкий тик
     *  делает системные вспышки экрана на ответе/отбое заметными глазом. */
    private suspend fun waitIdleKeepingSilent(budgetMs: Long, app: Context, am: AudioManager, silentToOwner: Boolean) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !idle) {
            runCatching { am.isMicrophoneMute = true }
            // ADJUST_MUTE, не явный индекс — см. комментарий у шага 2 в runFlow: индекс
            // привязан к конкретному аудио-маршруту (earpiece/speaker/bt_sco/...), а флаг мьюта
            // нет, так что повторный вызов безопасен независимо от того, куда уехал маршрут.
            if (silentToOwner) runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
            delay(30)
        }
    }

    /** Не блокирует основной сценарий — отдельная корутина, стартует сразу после play().
     *  Источник аудио штатного тапа INCALL_RECORD не документирован явно, поэтому проверяем
     *  все правдоподобные голосовые константы, а не одну конкретную. */
    private suspend fun checkOemRecorderStarted(app: Context) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callSources = setOf(
            android.media.MediaRecorder.AudioSource.VOICE_CALL,
            android.media.MediaRecorder.AudioSource.VOICE_DOWNLINK,
            android.media.MediaRecorder.AudioSource.VOICE_UPLINK,
        )
        val deadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < deadline) {
            val seen = runCatching {
                am.activeRecordingConfigurations.any { it.clientAudioSource in callSources }
            }.getOrDefault(false)
            if (seen) return
            delay(300)
        }
        EventLog(app).add("AM: штатный рекордер не подхватился за 4с — запись может не сохраниться")
    }

    private fun registerWatcher(ctx: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> offhook = true
                    TelephonyManager.EXTRA_STATE_IDLE -> { idle = true; offhook = false }
                }
            }
        }
        watcher = r
        // На API33+ (targetSdk=35) context-registered receiver БЕЗ флага EXPORTED/NOT_EXPORTED
        // падает с SecurityException при регистрации — это ловилось бы внешним try/catch в
        // onStartCommand и тихо обрывало весь сценарий на первом же шаге, ещё до ответа на
        // звонок. ContextCompat сам решает нужен ли флаг на текущем API.
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterWatcher(ctx: Context) {
        watcher?.let { runCatching { ctx.unregisterReceiver(it) } }
        watcher = null
    }

    private fun contactName(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            ctx.contentResolver.query(uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                CH, "Автоответчик", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = androidx.core.app.NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Автоответчик")
            .setContentText("Обрабатываю звонок…")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            runCatching { startForeground(NOTIF_ID, n) }
        }
    }

    private fun stopSelfSafe() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        watcher?.let { runCatching { unregisterReceiver(it) } }
        if (active === this) active = null
    }

    companion object {
        private const val CH = "answer_machine"
        private const val NOTIF_ID = 4711
        const val EX_NUMBER = "num"
        const val EX_NAME = "name"
        const val EX_REASON = "reason"
        const val EX_LANG = "lang"
        const val EX_TEXT = "text"
        const val EX_TEST_SEC = "test_sec"

        @Volatile private var active: AnswerMachineService? = null
        /** Есть ли сейчас идущая сессия — см. guard в onStartCommand. */
        @Volatile private var busy = false

        /** Вызывается из CallerOverlay по тапу «Принять» — сигнализирует в текущий
         *  экземпляр сервиса, тот же паттерн, что уже используют idle/offhook. */
        fun screeningAccept() { active?.let { it.accepted = true } }
        fun screeningDecline() { active?.let { it.declined = true } }
        fun screeningTransfer() { active?.let { it.transferred = true } }

        /** Ручная проверка блокировки экрана/тача БЕЗ звонка (та же защита от заморозки
         *  процесса, что и в реальном вызове — foreground-сервис). Вызывается из AmTestReceiver. */
        fun startTest(ctx: Context, seconds: Int) {
            val i = Intent(ctx, AnswerMachineService::class.java).apply {
                putExtra(EX_REASON, "test"); putExtra(EX_TEST_SEC, seconds)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                EventLog(ctx.applicationContext).add("AM ТЕСТ: не запустил сервис (${e.message})")
            }
        }

        /** Запустить автоответчик для входящего звонка. Вызывается из скрининга.
         *  [greetingText] — приветствие для этого клиента (напр. callPrompt из ЧС), null = общее. */
        fun start(ctx: Context, number: String?, name: String?, reason: String,
                  lang: String?, greetingText: String? = null) {
            val i = Intent(ctx, AnswerMachineService::class.java).apply {
                putExtra(EX_NUMBER, number); putExtra(EX_NAME, name)
                putExtra(EX_REASON, reason); putExtra(EX_LANG, lang)
                putExtra(EX_TEXT, greetingText)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                EventLog(ctx.applicationContext).add("AM: не запустил сервис (${e.message})")
            }
        }
    }
}
