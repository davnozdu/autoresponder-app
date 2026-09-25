package com.davnozdu.autoresponder.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.davnozdu.autoresponder.R
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.rules.AutoReplyState
import com.davnozdu.autoresponder.rules.ClosedState
import com.davnozdu.autoresponder.ui.HistoryActivity
import com.davnozdu.autoresponder.ui.StatsActivity

object AutoNotifications {
    const val CH_DND = "autoresp_dnd"
    const val CH_BLACKLIST = "autoresp_blacklist"
    const val ID_DND = 1001

    const val ACT_PAUSE_NEXT = "com.davnozdu.autoresponder.PAUSE_NEXT_DND"
    const val ACT_PAUSE_REBOOT = "com.davnozdu.autoresponder.PAUSE_REBOOT"
    const val ACT_DISABLE = "com.davnozdu.autoresponder.DISABLE"
    const val ACT_BL_NOTIFY = "com.davnozdu.autoresponder.BL_NOTIFY"
    const val ACT_QUIET_FLUSH = "com.davnozdu.autoresponder.QUIET_FLUSH"
    const val ID_BLACKLIST = 1003
    const val CH_DIGEST = "autoresp_digest"
    const val ID_DIGEST = 1004
    const val CH_AM_REC = "autoresp_am_rec"
    const val ID_AM_REC = 1005

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun ensureChannels(context: Context) {
        val m = nm(context) ?: return
        m.createNotificationChannel(NotificationChannel(CH_DND, "Автоответ активен",
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        // Отдельный канал «Сводка автоответа» слился со сводкой после DND — это одно и то же
        // уведомление. Старый канал удаляем, иначе он висит пустым в настройках телефона.
        m.deleteNotificationChannel("autoresp_summary")
        m.createNotificationChannel(NotificationChannel(CH_BLACKLIST, "Чёрный список",
            NotificationManager.IMPORTANCE_DEFAULT))
        m.createNotificationChannel(NotificationChannel(CH_DIGEST, "Сводка после «Не беспокоить»",
            NotificationManager.IMPORTANCE_DEFAULT))
        // IMPORTANCE_HIGH — чтобы всплывало (heads-up), а не тихо легло в шторку: пропущенное
        // голосовое сообщение — то же самое по важности, что и обычный пропущенный звонок.
        m.createNotificationChannel(NotificationChannel(CH_AM_REC, "Автоответчик: новое сообщение",
            NotificationManager.IMPORTANCE_HIGH))
    }

    /** Реакция на смену режима DND. */
    fun onDndChanged(context: Context) {
        val app = context.applicationContext
        val s = Settings(app)
        val dndOn = ClosedState.isDndOn(app)
        if (dndOn) {
            if (!s.dndWasOn) {
                s.dndWasOn = true; s.lastDndOnTime = System.currentTimeMillis()
                // Личное время: пока DND включён, скрининг с карточкой не нужен — весь трафик
                // молча идёт на голосовой автоответчик, как любой другой «закрытый» звонок.
                if (s.autoScreeningByDnd) s.screeningEnabled = false
                DndStats.startSession(app)
                // Момент, ради которого мост и нужен: владелец закончил день и включил
                // «Не беспокоить». Всё, что он писал клиентам руками, уведомлений не
                // порождало — подбираем это разом, пока никто не ждёт ответа. Иначе
                // клиент отвечает на дневное сообщение, а робот здоровается заново.
                Thread {
                    com.davnozdu.autoresponder.store.SmsWatcher.drain(app)
                    com.davnozdu.autoresponder.store.MsgrBridge.sync(app, force = true)
                }.start()
            }
            // Показываем и на повторных срабатываниях: после перезагрузки система чистит
            // панель, а счётчики лежат в настройках — уведомление надо вернуть.
            // Выключенный автоответ ничего не делает: «Автоответ работает» в панели было бы
            // враньём, а раньше уведомление появлялось и после кнопки «Выключить».
            if (s.notificationsEnabled && s.enabled) showDndActive(app, s)
        } else if (s.dndWasOn) {
            val from = s.lastDndOnTime
            s.dndWasOn = false
            // Рабочее время вернулось — скрининг обратно.
            if (s.autoScreeningByDnd) s.screeningEnabled = true
            AutoReplyState.onDndOff(app)
            cancelDnd(app)
            // Сводка — здесь, а не в назначенный час: телефон только что взяли в руки,
            // и видно, что случилось за только что закончившийся сеанс.
            if (s.notificationsEnabled && s.digestEnabled) Digest.show(app, s, from)
        }
    }

    private fun action(context: Context, act: String, id: Int): PendingIntent {
        val i = Intent(context, NotifActionReceiver::class.java).setAction(act)
        return PendingIntent.getBroadcast(context, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Постоянное уведомление на время DND. Показывает не «работает», а что уже случилось:
     * сколько звонков и сообщений пришло и сколько ответов ушло. Текст пересобирается
     * только в момент события (см. [DndStats]) — никакого таймера, телефон не будим.
     */
    fun showDndActive(context: Context, s: Settings = Settings(context)) {
        ensureChannels(context)
        val paused = AutoReplyState.isPaused(context)
        val stats = DndStats.line(s)
        val tap = PendingIntent.getActivity(context, 13,
            Intent(context, StatsActivity::class.java)
                .putExtra("from", s.lastDndOnTime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(context, CH_DND)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(if (paused) "Автоответ на паузе" else "Автоответ работает")
            .setContentText(stats)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                if (paused) "$stats\nПауза активна — новым никто не отвечает."
                else "$stats\nОтвечаю на звонки и сообщения, пока включён «Не беспокоить»."))
            .setContentIntent(tap)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .addAction(0, "До след. DND", action(context, ACT_PAUSE_NEXT, 1))
            .addAction(0, "До перезагрузки", action(context, ACT_PAUSE_REBOOT, 2))
            .addAction(0, "Выключить", action(context, ACT_DISABLE, 3))
            .build()
        nm(context)?.notify(ID_DND, n)
    }

    fun cancelDnd(context: Context) { nm(context)?.cancel(ID_DND) }

    /** Всплывающее уведомление — клиент оставил сообщение на голосовом автоответчике (любой
     *  повод: закрыто/гарнитура/ЧС/скрининг-без-ответа/переброс на автоответчик). Один слот на
     *  все такие уведомления (ID_AM_REC) — новое заменяет предыдущее, а не копится стопкой;
     *  общее число непрослушанных — в тексте, из той же БД, что и счётчик в самом приложении. */
    fun showAmRec(context: Context, name: String?, number: String?, durationMs: Long) {
        ensureChannels(context)
        val newCount = com.davnozdu.autoresponder.store.HistoryDb.get(context).amRecNewCount()
        val who = name ?: number ?: "Неизвестный"
        val durSec = durationMs / 1000
        val tap = PendingIntent.getActivity(context, 14,
            Intent(context, com.davnozdu.autoresponder.ui.AmRecordingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(context, CH_AM_REC)
            .setSmallIcon(android.R.drawable.stat_notify_voicemail)
            .setContentTitle("Автоответчик: $who")
            .setContentText("Оставил сообщение (${durSec}с) · новых всего: $newCount")
            .setContentIntent(tap).setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        nm(context)?.notify(ID_AM_REC, n)
    }

    fun showBlacklist(context: Context, count: Int, names: String) {
        ensureChannels(context)
        val tap = PendingIntent.getActivity(context, 11,
            Intent(context, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(context, CH_BLACKLIST)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Чёрный список: пытались связаться ($count)")
            .setContentText(names.ifBlank { "Контакты из чёрного списка" })
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(names))
            .setContentIntent(tap).setAutoCancel(true).build()
        nm(context)?.notify(ID_BLACKLIST, n)
    }

    /** Сводка после DND. Ведёт в «Требуют ответа», если есть кому отвечать. */
    fun showDigest(context: Context, title: String, text: String, pending: Int) {
        ensureChannels(context)
        val target = if (pending > 0) Intent(context, com.davnozdu.autoresponder.ui.InboxActivity::class.java)
                     else Intent(context, HistoryActivity::class.java)
        val tap = PendingIntent.getActivity(context, 12,
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(context, CH_DIGEST)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title).setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap).setAutoCancel(true).build()
        nm(context)?.notify(ID_DIGEST, n)
    }
}
