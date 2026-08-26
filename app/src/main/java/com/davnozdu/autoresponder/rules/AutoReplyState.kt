package com.davnozdu.autoresponder.rules

import android.content.Context
import android.os.SystemClock
import com.davnozdu.autoresponder.data.Settings

/** Состояние паузы авто-ответа (тумблеры из уведомления DND). */
object AutoReplyState {
    fun isPaused(context: Context): Boolean {
        val s = Settings(context)
        return when (s.pauseMode) {
            1 -> true                              // до следующего DND (снимется при выключении DND)
            // До перезагрузки: аптайм монотонно растёт внутри одной загрузки и обнуляется при
            // рестарте. Сравнение «стеночных» часов (wallclock − uptime) с окном 5 с срывалось
            // от обычной синхронизации времени по NTP и снимало паузу само собой.
            2 -> SystemClock.elapsedRealtime() >= s.pauseBootMarker
            else -> false
        }
    }
    fun pauseUntilNextDnd(context: Context) { Settings(context).pauseMode = 1 }
    fun pauseUntilReboot(context: Context) {
        val s = Settings(context); s.pauseMode = 2; s.pauseBootMarker = SystemClock.elapsedRealtime()
    }
    fun resume(context: Context) { Settings(context).pauseMode = 0 }
    /** Вызывать при ВЫКЛючении DND — снимает «до следующего DND». */
    fun onDndOff(context: Context) { val s = Settings(context); if (s.pauseMode == 1) s.pauseMode = 0 }
}
