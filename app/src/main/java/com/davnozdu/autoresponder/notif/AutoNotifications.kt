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
    }

    /** Реакция на смену режима DND. */
    fun onDndChanged(context: Context) {
        val app = context.applicationContext
        val s = Settings(app)
        val dndOn = ClosedState.isDndOn(app)
        if (dndOn) {
            if (!s.dndWasOn) {
                s.dndWasOn = true; s.lastDndOnTime = System.currentTimeMillis()
                DndStats.startSession(app)
            }
            // Показываем и на повторных срабатываниях: после перезагрузки система чистит
            // панель, а счётчики лежат в настройках — уведомление надо вернуть.
            // Выключенный автоответ ничего не делает: «Автоответ работает» в панели было бы
            // враньём, а раньше уведомление появлялось и после кнопки «Выключить».
            if (s.notificationsEnabled && s.enabled) showDndActive(app, s)
        } else if (s.dndWasOn) {
            val from = s.lastDndOnTime
            s.dndWasOn = false
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
