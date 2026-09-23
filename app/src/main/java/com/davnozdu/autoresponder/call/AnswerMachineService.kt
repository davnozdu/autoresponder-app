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
    private var watcher: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val number = intent?.getStringExtra(EX_NUMBER)
        val name = intent?.getStringExtra(EX_NAME)
        val reason = intent?.getStringExtra(EX_REASON) ?: "closed"
        val lang = intent?.getStringExtra(EX_LANG)
        val text = intent?.getStringExtra(EX_TEXT)
        scope.launch {
            try { runFlow(number, name, reason, lang, text) }
            catch (e: Exception) { EventLog(applicationContext).add("AM: сбой (${e.message})") }
            finally { stopSelfSafe() }
        }
        return START_NOT_STICKY
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

            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 2) Заглушить: микрофон (абонент не слышит комнату) и, в тихом режиме, вывод к владельцу.
            val prevMute = am.isMicrophoneMute
            runCatching { am.isMicrophoneMute = true }
            var prevVol = -1
            if (s.amSilentToOwner) {
                prevVol = runCatching { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(-1)
                runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0) }
                AmBridge.muteOut(app, true)
            }

            // 3) Приветствие + длинный бип в линию (один файл — без щелчка между проигрываниями).
            // Доп. пояс безопасности поверх таймаутов внутри Greeting: звонок не должен
            // зависнуть целиком, если где-то в цепочке TTS/конвертации что-то пойдёт не так.
            val greet = kotlinx.coroutines.withTimeoutOrNull(15_000L) { Greeting.prepare(app, lang, greetingText) }
            if (greet != null) AmBridge.play(app, greet, 1)
            else EventLog(app).add("AM: приветствие не готово — молчим")

            // 4) Держим линию под сообщение клиента, пока не положит трубку или не выйдет таймаут.
            waitIdleOr(s.amMaxMessageSec.coerceIn(5, 300) * 1000L)

            // 5) Отбой, если ещё не завершён.
            if (!idle) endCall(app)

            // 6) Вернуть звук.
            AmBridge.stop(app)
            runCatching { am.isMicrophoneMute = prevMute }
            if (prevVol >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVol, 0) }
            if (s.amSilentToOwner) AmBridge.muteOut(app, false)

            // 7) Дождаться простоя и подобрать запись звонилки → отдельная папка + журнал.
            waitIdleOr(5_000L)
            val link = RecordingLinker.linkLatest(app, number, start)
            if (link != null) db.amRecSetFile(recId, link.first, link.second)
            else db.amRecSetFile(recId, "", System.currentTimeMillis() - start)

            EventLog(app).add("AM: завершено ${number ?: "?"}")
        } finally {
            // Гарантированно снимаем ресивер даже при раннем return/исключении — иначе
            // он остаётся висеть до onDestroy() (не течёт, но грязно).
            unregisterWatcher(app)
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
    }

    companion object {
        private const val CH = "answer_machine"
        private const val NOTIF_ID = 4711
        const val EX_NUMBER = "num"
        const val EX_NAME = "name"
        const val EX_REASON = "reason"
        const val EX_LANG = "lang"
        const val EX_TEXT = "text"

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
