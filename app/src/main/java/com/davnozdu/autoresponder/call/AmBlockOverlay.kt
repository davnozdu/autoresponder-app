package com.davnozdu.autoresponder.call

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.davnozdu.autoresponder.data.EventLog

/**
 * Полноэкранная непрозрачная накладка поверх экрана звонилки на время работы голосового
 * автоответчика — глотает ВСЕ касания, чтобы владелец случайно не сбросил звонок (задел
 * карман, увидел экран и потянулся к нему). Без кнопок — трогать на ней нечего.
 *
 * В отличие от [com.davnozdu.autoresponder.notif.CallerOverlay] (карточка, не мешает
 * управлять звонком) — тут ровно наоборот: MATCH_PARENT и БЕЗ FLAG_NOT_TOUCH_MODAL, окно
 * перехватывает весь экран. Показываем сразу после подтверждённого ответа, убираем в finally
 * вместе со снятием ресивера — иначе залипшая чёрная накладка была бы худшим исходом.
 */
object AmBlockOverlay {
    // Заметно больше макс. длительности потока автоответчика (таймаут сообщения ≤300с + запас
    // на ответ/копирование записи) — сработать НЕ должно в норме, это только подстраховка на
    // случай, если процесс убьют жёстко и finally в AnswerMachineService не выполнится.
    private const val TIMEOUT_MS = 6 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null

    fun show(context: Context) {
        val app = context.applicationContext
        if (!AndroidSettings.canDrawOverlays(app)) return   // модуль ещё не выдал — молча живём без блокировки
        main.post {
            runCatching {
                hideNow(app)
                val view = build(app)
                wm(app).addView(view, params())
                shown = view
                main.postDelayed({ hide(app) }, TIMEOUT_MS)
            }.onFailure { EventLog(app).add("AM оверлей: не показать — ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        main.post { runCatching { hideNow(app) } }
    }

    private fun hideNow(app: Context) {
        shown?.let { runCatching { wm(app).removeView(it) } }
        shown = null
        main.removeCallbacksAndMessages(null)
    }

    private fun wm(c: Context) = c.getSystemService(WindowManager::class.java)

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // не хватать клавиатурный фокус; касания при этом ловятся
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER }

    private fun build(app: Context): View {
        val d = app.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        return LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E6000000"))
            isClickable = true; isFocusable = false   // просто глотать касания, ничего не делать по ним
            addView(TextView(app).apply {
                text = "🤖 Автоответчик обрабатывает звонок"
                setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
                setPadding(px(24), px(8), px(24), px(4))
            })
            addView(TextView(app).apply {
                text = "Экран заблокирован до завершения звонка"
                setTextColor(Color.parseColor("#B0B0B6")); textSize = 14f; gravity = Gravity.CENTER
                setPadding(px(24), 0, px(24), px(8))
            })
        }
    }
}
