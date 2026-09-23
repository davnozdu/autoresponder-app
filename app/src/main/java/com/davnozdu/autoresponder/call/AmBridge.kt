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

    /** Есть ли демон модуля: он создаёт папку при старте. Приложение всё равно создаёт её
     *  само, чтобы записать запрос — inotifyd подхватит, когда/если модуль запустится. */
    fun available(ctx: Context): Boolean = dir(ctx).isDirectory

    private fun write(ctx: Context, line: String) {
        val app = ctx.applicationContext
        try {
            val d = dir(app); if (!d.isDirectory) d.mkdirs()
            req(app).writeText(line)
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
}
