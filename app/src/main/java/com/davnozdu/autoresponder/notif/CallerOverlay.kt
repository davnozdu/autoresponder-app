package com.davnozdu.autoresponder.notif

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.davnozdu.autoresponder.crm.CrmLookup
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.respond.Handoff

/**
 * Карточка звонящего поверх экрана звонка.
 *
 * Окно не перехватывает касания мимо себя (NOT_FOCUSABLE + NOT_TOUCH_MODAL), поэтому
 * «принять» и «сбросить» у звонилки работают как обычно. Высота — по содержимому, но не
 * выше 30% экрана; дальше прокрутка внутри.
 *
 * Худший отказ здесь — залипшее окно поверх всего, поэтому снимается оно тремя
 * независимыми способами: крестиком, по завершению звонка и по таймауту.
 */
object CallerOverlay {
    private const val TIMEOUT_MS = 2 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null
    private var shownFor: String? = null
    private data class AcceptDecline(val onAccept: () -> Unit, val onDecline: () -> Unit)
    private var acceptDecline: AcceptDecline? = null
    // Отдельный ключ, а НЕ shownFor: до первой отрисовки shownFor == null, и сравнение
    // «shownFor != number» (null != "+420…") было true — кнопки стирались ДО того, как
    // карточка вообще появлялась на экране, для любого звонящего без готовой CRM-карточки
    // (типичный случай — незнакомый номер, ровно то, для чего скрининг существует). Найдено
    // ревью ветки. Сравниваем с этим ключом, а не с «что сейчас на экране».
    private var acceptDeclineFor: String? = null
    // Именной Runnable для авто-hide, а НЕ removeCallbacksAndMessages(null): тот стирал
    // ЛЮБЫЕ отложенные post() на этом Handler'е — включая параллельный ещё не выполнившийся
    // show() из CallerCardNotifier.onIncoming (карточка с CRM-данными приходит на том же
    // Handler'е чуть позже). Гонка, найденная ревью ветки: тот, чей post() выполнится первым,
    // стирал post второго — карточка/кнопки второго вызова никогда не отрисовывались.
    private var autoHide: Runnable? = null

    fun show(context: Context, number: String, lookup: CrmLookup?) {
        val app = context.applicationContext
        if (!AndroidSettings.canDrawOverlays(app)) return   // модуль ещё не выдал — молча живём уведомлением
        main.post {
            runCatching {
                // Разные звонки — старые кнопки Ответить/Отклонить не имеют смысла для НОВОГО
                // номера. Тот же номер (перерисовка карточки после прихода данных CRM из
                // CallerCardNotifier.onIncoming) — кнопки сохраняются, см. showScreening.
                if (acceptDeclineFor != null && acceptDeclineFor != number) {
                    acceptDecline = null; acceptDeclineFor = null
                }
                val screening = acceptDecline != null
                removeCurrentView(app)
                val card = CallerCard.render(lookup?.name?.ifBlank { null }, number, lookup)
                val view = build(app, number, card, lookup)
                wm(app).addView(view, params(app, wake = screening))
                shown = view
                shownFor = number
                // Скрининг сам владеет своим временем жизни (таймаут — amMaxMessageSec внутри
                // AnswerMachineService.waitScreeningDecision, может быть больше 2 минут) — свой
                // авто-hide тут только мешал бы, стирая кнопки раньше срока. Обычная карточка
                // (без кнопок) — как раньше, 2 минуты и убралась сама.
                if (!screening) {
                    val r = Runnable { hide(app) }
                    autoHide = r
                    main.postDelayed(r, TIMEOUT_MS)
                }
            }.onFailure { EventLog(app).add("ОКНО: не показать — ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** Как [show], но с кнопками «Ответить»/«Отклонить» — для интерактивного скрининга
     *  (см. AnswerMachineService.runScreeningFlow). [onAccept]/[onDecline] вызываются на
     *  главном потоке при нажатии; карточка убирается сама сразу по первому тапу — второй
     *  тап (двойное нажатие) бьёт по уже отсутствующей вью. Крестик/шапка во время скрининга
     *  трактуются как «Отклонить», а не тихое закрытие — иначе абонент повисал бы без решения. */
    fun showScreening(context: Context, number: String, lookup: CrmLookup?,
                       onAccept: () -> Unit, onDecline: () -> Unit) {
        acceptDecline = AcceptDecline(onAccept, onDecline)
        acceptDeclineFor = number
        show(context, number, lookup)
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        main.post { runCatching { removeCurrentView(app); acceptDecline = null; acceptDeclineFor = null } }
    }

    /** Крестик/шапка: во время скрининга закрытие карточки БЕЗ решения оставляло бы
     *  абонента висеть под зуммером до полного таймаута, а владельца — без возможности
     *  передумать («Ответить» уже пропало вместе с карточкой). Трактуем закрытие как явный
     *  отказ — тот же путь, что и кнопка «Отклонить». Обычная (не скрининговая) карточка —
     *  как раньше, просто прячется. */
    private fun closeOrDecline(app: Context) {
        val ad = acceptDecline
        hide(app)
        ad?.onDecline()
    }

    private fun removeCurrentView(app: Context) {
        shown?.let { runCatching { wm(app).removeView(it) } }
        shown = null
        shownFor = null
        autoHide?.let { main.removeCallbacks(it) }
        autoHide = null
    }

    private fun wm(c: Context) = c.getSystemService(WindowManager::class.java)

    /** [wake] — только для скрининга: телефон в этот момент чаще всего лежит с погашенным/
     *  заблокированным экраном (никто его не ждёт держащим в руке, в отличие от обычного
     *  входящего звонка) — окно добавляется («addWindow», реально видимое по WindowManager),
     *  но БЕЗ этих флагов ничего физически не видно на погасшем/залоченном экране. Найдено
     *  живым звонком: окно создавалось (WindowManager: addWindow, viewVisibility=0), а
     *  владелец ничего не видел — mWakefulness=Dozing в момент звонка. Обычная (не
     *  скрининговая) карточка эти флаги не получает — во время звонка, на который и так
     *  звонит система, экран обычно уже включён. */
    private fun params(app: Context, wake: Boolean = false) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (wake) WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
             else 0)),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        // Живой звонок поймал: системная «таблетка» активного звонка (имя+таймер+отбой)
        // рисуется ПОВЕРХ нашего окна и закрывает верх карточки (имя/статус заказа) — видны
        // остаются только нижние ряды кнопок. Systembar-инсеты её не покрывают (это не
        // статус-бар, отдельный плавающий чип), поэтому сдвигаем окно целиком вниз на
        // фиксированный отступ (эмпирически — высота статус-бара + сама таблетка).
        val statusBar = app.resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) app.resources.getDimensionPixelSize(it) else 0 }
        y = statusBar + (96 * app.resources.displayMetrics.density).toInt()
    }

    private fun build(app: Context, number: String, card: CallerCard.Card, lookup: CrmLookup?): View {
        val d = app.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F21C1C1E"))
                cornerRadius = px(16).toFloat()
            }
        }
        // Шапка: имя и крестик. Нажатие по шапке тоже закрывает — но только по шапке,
        // иначе прокрутка тела вступала бы в спор с закрытием.
        val head = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(app).apply {
            text = HtmlCompat.fromHtml("<b>${card.title}</b>", HtmlCompat.FROM_HTML_MODE_LEGACY)
            setTextColor(Color.WHITE); textSize = 19f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        head.addView(TextView(app).apply {
            text = "✕"; setTextColor(Color.parseColor("#9E9EA3")); textSize = 20f
            setPadding(px(12), 0, px(4), 0)
            setOnClickListener { closeOrDecline(app) }
        })
        head.setOnClickListener { closeOrDecline(app) }
        root.addView(head)

        root.addView(TextView(app).apply {
            text = card.summary
            setTextColor(Color.parseColor("#E5E5EA")); textSize = 15f
            setPadding(0, px(4), 0, 0)
        })
        if (card.details.isNotBlank()) {
            root.addView(CappedScroll(app, (app.resources.displayMetrics.heightPixels * 0.30).toInt()).apply {
                addView(TextView(app).apply {
                    text = card.details
                    setTextColor(Color.parseColor("#B0B0B6")); textSize = 14f
                    setPadding(0, px(4), 0, 0)
                })
            })
        }
        if (lookup != null && lookup.records.isNotEmpty()) root.addView(actions(app, number, px(8)))
        acceptDecline?.let { ad -> root.addView(acceptDeclineRow(app, px(8), ad)) }
        return root
    }

    private fun acceptDeclineRow(app: Context, gap: Int, ad: AcceptDecline): View {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        // Крупные кнопки, чтобы не промахнуться: высота — ещё +10% высоты экрана поверх
        // тех ~30%, что уже занимает карточка (details у CallerCard ограничены 30%, см. build()).
        val btnHeight = (app.resources.displayMetrics.heightPixels * 0.10).toInt()
        fun button(label: String, color: String, onClick: () -> Unit) = Button(app).apply {
            text = label; textSize = 20f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color)); cornerRadius = gap.toFloat() * 1.5f
            }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f).apply { marginEnd = gap }
            // hide() СРАЗУ по тапу — второй тап (двойное нажатие) бьёт по пустому месту,
            // не по кнопке: гонка «оба нажаты» физически исключена.
            setOnClickListener { hide(app); onClick() }
        }
        row.addView(button("Ответить", "#0A6E2E") { ad.onAccept() })
        row.addView(button("Отклонить", "#8E1B1B") { ad.onDecline() })
        return row
    }

    private fun actions(app: Context, number: String, gap: Int): View {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        fun button(label: String, onClick: () -> Unit) = Button(app).apply {
            text = label; textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2E")); cornerRadius = gap.toFloat() * 1.5f
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap }
            setOnClickListener { onClick() }
        }
        // Шаг первый: спросить. Живому клиенту SMS с одного касания уходить не должно.
        row.addView(button("Статус по SMS") { confirm(app, number, row) })
        row.addView(button("Отвечаю я") {
            Handoff.pause(app, number, "sms", 60)
            EventLog(app).add("ОКНО $number — владелец отвечает сам, час")
            hide(app)
        })
        return row
    }

    private fun confirm(app: Context, number: String, row: LinearLayout) {
        row.removeAllViews()
        val gap = (8 * app.resources.displayMetrics.density).toInt()
        fun button(label: String, color: String, onClick: () -> Unit) = Button(app).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = gap * 1.5f }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap }
            setOnClickListener { onClick() }
        }
        row.addView(button("Отправить", "#0A6E2E") {
            app.sendBroadcast(Intent(app, CallerCardReceiver::class.java).apply {
                action = CallerCardNotifier.ACTION_SEND
                putExtra(CallerCardNotifier.EXTRA_NUMBER, number)
            })
            hide(app)
        })
        row.addView(button("Отмена", "#2C2C2E") { hide(app) })
    }

    /** Растёт по содержимому, но не выше потолка; дальше прокручивается. */
    private class CappedScroll(context: Context, private val maxH: Int) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) =
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}
