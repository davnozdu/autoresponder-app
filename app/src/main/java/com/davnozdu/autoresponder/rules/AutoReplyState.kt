package com.davnozdu.autoresponder.rules

import android.content.Context
import android.provider.Settings as AndroidSettings
import com.davnozdu.autoresponder.data.Settings

/** Состояние паузы авто-ответа (тумблеры из уведомления DND). */
object AutoReplyState {
    /** Растёт на 1 при каждой реальной перезагрузке и никогда не сбрасывается сам — в
     *  отличие от [android.os.SystemClock.elapsedRealtime], которым маркер хранился раньше:
     *  после паузы новый аптайм стартует от нуля, а сохранённый маркер (например, 14ч из
     *  предыдущей загрузки) остаётся большим — и пауза бесшумно возвращалась сама, как
     *  только новый аптайм дорастал до старого значения маркера. BOOT_COUNT меняется
     *  только на реальном ребуте, дорасти до старого значения нечем. */
    private fun bootCount(context: Context): Long =
        AndroidSettings.Global.getInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT, 0).toLong()

    fun isPaused(context: Context): Boolean {
        val s = Settings(context)
        return when (s.pauseMode) {
            1 -> true                              // до следующего DND (снимется при выключении DND)
            2 -> s.pauseBootMarker == bootCount(context) // до перезагрузки: маркер = boot count на момент паузы
            else -> false
        }
    }
    fun pauseUntilNextDnd(context: Context) { Settings(context).pauseMode = 1 }
    fun pauseUntilReboot(context: Context) {
        val s = Settings(context); s.pauseMode = 2; s.pauseBootMarker = bootCount(context)
    }
    fun resume(context: Context) { Settings(context).pauseMode = 0 }
    /** Вызывать при ВЫКЛючении DND — снимает «до следующего DND». */
    fun onDndOff(context: Context) { val s = Settings(context); if (s.pauseMode == 1) s.pauseMode = 0 }
}
