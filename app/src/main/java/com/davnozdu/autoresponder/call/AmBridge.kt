package com.davnozdu.autoresponder.call

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import java.io.File

/**
 * Мост к root-демону автоответчика (`answermachine.sh` в модуле).
 *
 * На этом AIDL-AHAL поток incall-music выбирается только нативным output-флагом — из Java
 * его не запросить. Поэтому проигрывание приветствия в линию делает нативный `pal_inject`
 * под root, а приложение лишь шлёт ему команду файлом (как MsgrBridge для баз мессенджеров).
 *
 * Протокол — одна строка в `files/am/req`:
 *   play <raw_pcm_abspath> <loops>   проиграть приветствие (PCM 48к/16/stereo)
 *   stop                              снять проигрывание
 *   muteout on|off                    root-fallback заглушения вывода владельцу
 *
 * Модуля может не быть — тогда req никто не читает; приложение это переживает (приветствие
 * просто не прозвучит, остальной автоответчик работает).
 */
object AmBridge {
    private const val DIR = "am"

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR)
    private fun req(ctx: Context) = File(dir(ctx), "req")
    private fun resp(ctx: Context) = File(dir(ctx), "resp")

    /** Есть ли демон модуля: он создаёт папку при старте. Приложение всё равно создаёт её
     *  само, чтобы записать запрос — inotifyd подхватит, когда/если модуль запустится. */
    fun available(ctx: Context): Boolean = dir(ctx).isDirectory

    /** `req` — один файл, не очередь: если следующая команда пишется раньше, чем демон успел
     *  прочитать предыдущую, она просто перетирает её без следа. Поймали на живом звонке:
     *  redim() в цикле ожидания шёл сразу за play() без всякой паузы (раньше между командами
     *  естественно была пауза, с появлением блокировки экрана — не стало) — play терялся,
     *  приветствие не звучало, хотя демон был жив и остальные команды исправно доходили.
     *  Поэтому здесь ждём ответ демона (reply() в answermachine.sh пишет его на КАЖДУЮ
     *  команду) перед тем как разрешить следующей команде перезаписать req; не дождались —
     *  не страшно, отвечаем по факту таймаута и едем дальше (демон может быть не установлен). */
    private const val ACK_TIMEOUT_MS = 400L
    private const val ACK_POLL_MS = 15L

    private fun write(ctx: Context, line: String) {
        val app = ctx.applicationContext
        try {
            val d = dir(app); if (!d.isDirectory) d.mkdirs()
            val respFile = resp(app)
            val before = runCatching { respFile.readText() }.getOrDefault("")
            req(app).writeText(line)
            val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val now = runCatching { respFile.readText() }.getOrDefault("")
                if (now.isNotEmpty() && now != before) return
                Thread.sleep(ACK_POLL_MS)
            }
        } catch (e: Exception) {
            EventLog(app).add("AM мост: не удалось послать «$line» (${e.message})")
        }
    }

    /** Проиграть приветствие (raw PCM 48к/16/stereo) в исходящий канал активного вызова. */
    fun play(ctx: Context, rawPcmPath: String, loops: Int) =
        write(ctx, "play $rawPcmPath ${loops.coerceAtLeast(1)}")

    /** Выключить экран (если сейчас включён) — обычному приложению это недоступно без root. */
    fun screenOff(ctx: Context) = write(ctx, "screenoff")

    fun stop(ctx: Context) = write(ctx, "stop")

    /** Root-fallback заглушения вывода на владельца (если setStreamVolume(0) недостаточно). */
    fun muteOut(ctx: Context, on: Boolean) = write(ctx, "muteout ${if (on) "on" else "off"}")

    /** Железно: подсветка в 0 через /sys/class/backlight + тачскрин выключен на уровне ядра
     *  (inhibit-интерфейс input-подсистемы) — не зависит от Keyguard/DisplayManager, в отличие
     *  от [screenOff]. Портировано из github.com/davnozdu/vr-usb-monitor. Демон сам следит за
     *  [appPid] root-сторожем и откатит блокировку, если процесс исчезнет без [blockOff]. */
    fun blockOn(ctx: Context, appPid: Int) = write(ctx, "blockon $appPid")

    /** DisplayManager перебивает подсветку через пару секунд — звать на каждом тике ожидания. */
    fun redim(ctx: Context) = write(ctx, "redim")

    fun blockOff(ctx: Context) = write(ctx, "blockoff")

    /** Своя запись звонка (incall-record тап через pal_record) — fallback на случай, если
     *  штатный рекордер OxygenOS не подхватится (видели ~1 звонок из 4 без файла вовсе).
     *  Пишем параллельно штатному с самого начала звонка; приложение решает после звонка,
     *  какой из двух файлов оставить. [maxSeconds] — тот же лимит, что и на ожидание сообщения. */
    fun recStart(ctx: Context, wavPath: String, maxSeconds: Int) =
        write(ctx, "recstart $wavPath ${maxSeconds.coerceAtLeast(5)}")

    fun recStop(ctx: Context) = write(ctx, "recstop")
}
