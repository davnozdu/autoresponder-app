package com.davnozdu.autoresponder.rules

import android.content.Context
import android.os.SystemClock
import com.davnozdu.autoresponder.data.Settings

/** Состояние паузы авто-ответа (тумблеры из уведомления DND). */
object AutoReplyState {
    private fun bootWall() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    fun isPaused(context: Context): Boolean {
        val s = Settings(context)
        return when (s.pauseMode) {
            1 -> true                              // до следующего DND (снимется при выключении DND)
            2 -> kotlin.math.abs(s.pauseBootMarker - bootWall()) < 5000  // до перезагрузки (та же загрузка)
            else -> false
        }
    }
    fun pauseUntilNextDnd(context: Context) { Settings(context).pauseMode = 1 }
    fun pauseUntilReboot(context: Context) {
        val s = Settings(context); s.pauseMode = 2; s.pauseBootMarker = bootWall()
    }
    fun resume(context: Context) { Settings(context).pauseMode = 0 }
    /** Вызывать при ВЫКЛючении DND — снимает «до следующего DND». */
    fun onDndOff(context: Context) { val s = Settings(context); if (s.pauseMode == 1) s.pauseMode = 0 }
}
