package com.davnozdu.autoresponder.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings

/** Ловит входящие сообщения выбранных мессенджеров через уведомления. */
class NotifListenerService : NotificationListenerService() {

    private val messagesPkg = "com.google.android.apps.messaging"

    private val dndReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { AutoNotifications.onDndChanged(c) }
    }

    override fun onListenerConnected() {
        instance = this
        AutoNotifications.ensureChannels(this)
        try {
            registerReceiver(dndReceiver, android.content.IntentFilter(
                android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                android.content.Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
        AutoNotifications.onDndChanged(this)  // синхронизировать текущее состояние
        com.davnozdu.autoresponder.store.Backup.schedule(this)  // ежедневный бэкап БД
        com.davnozdu.autoresponder.store.Heartbeat.tick(this)   // признак жизни для модуля
        // Журнал переписки ведётся постоянно, а не только по тому, что показали уведомления:
        // исходящие SMS владельца ловим через провайдер, переписку мессенджеров — через
        // root-мост модуля. Процесс держится живым этим же слушателем, поэтому и заводим здесь.
        com.davnozdu.autoresponder.store.SmsWatcher.start(this)
        Thread { com.davnozdu.autoresponder.store.MsgrBridge.sync(applicationContext) }.start()
        // Восстановление после простоя: слушатель мог быть отвязан (падение процесса, ре-бинд
        // watchdog'ом, перезагрузка) — на переподключении система НЕ переигрывает onNotificationPosted,
        // поэтому активно подхватываем ещё свежие непрочитанные. Возрастной фильтр (5 мин) внутри
        // handlePosted отсекает старьё, а персистентный ReplyStore не даёт ответить повторно.
        try { activeNotifications?.forEach { handlePosted(it) } } catch (_: Exception) {}
    }
    override fun onListenerDisconnected() {
        instance = null
        try { unregisterReceiver(dndReceiver) } catch (_: Exception) {}
        com.davnozdu.autoresponder.store.SmsWatcher.stop(this)
    }

    companion object {
        @Volatile private var instance: NotifListenerService? = null
        /** Подключён ли слушатель на самом деле — уходит в heartbeat для модуля. */
        val isConnected: Boolean get() = instance != null
        /** Снять уведомление после ответа, чтобы не обрабатывать повторно. */
        fun dismiss(key: String?) {
            if (key == null) return
            try { instance?.cancelNotification(key) } catch (_: Exception) {}
        }
    }

    private fun tagFor(pkg: String): String = when (pkg) {
        messagesPkg -> "rcs"
        "com.whatsapp", "com.whatsapp.w4b" -> "whatsapp"
        "org.telegram.messenger" -> "telegram"
        else -> try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)).toString().lowercase()
        } catch (e: Exception) { pkg.substringAfterLast('.') }
    }

    /**
     * Личный ли это чат Telegram — по id канала уведомления.
     *
     * Telegram называет каналы `<аккаунт>channel_<тип>_<хэш>`, где тип — `private`, `groups`,
     * `channel`, `silent` и так далее. Проверка «содержит private» отсекала лишнее: у чата с
     * приглушёнными уведомлениями тип `silent`, и личная переписка молча пропускалась. Поэтому
     * не ищем разрешённое, а отбрасываем заведомо неличное; незнакомый тип пропускаем дальше —
     * группу всё равно отсеет `isGroupConversation`, а «Избранные» и маска отсеют не-клиента.
     */
    private fun isTelegramPrivate(channelId: String?): Boolean {
        val id = (channelId ?: "").lowercase()
        val type = id.substringAfter("channel_", "").substringBefore('_')
        return type !in setOf("groups", "group", "channel", "channels", "stories", "reactions")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handlePosted(sbn)

    private fun handlePosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            val s = Settings(this)
            if (pkg !in s.monitoredApps) return
            val n = sbn.notification ?: return
            // Дальше уведомление НАШЕ (пакет отслеживается) — каждый отказ логируем,
            // иначе «в журнале пусто» невозможно отличить от «уведомление не пришло».
            val tag = tagFor(pkg)
            fun drop(why: String) { EventLog(this).add("NOTIF[$tag] пропуск: $why") }

            // Сводка группы уведомлений и постоянное уведомление сообщениями не являются
            // вообще — их отсекаем до разбора, иначе в журнал полезут дубли и служебные строки.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { drop("сводка группы уведомлений"); return }
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) { drop("постоянное уведомление"); return }

            // Канал или группа Telegram — это вообще не личная переписка: ни отвечать, ни
            // писать в журнал. Проверка стоит до разбора, иначе посты каналов заводили бы
            // в истории собственные ветки и вытесняли разговоры с клиентами.
            if (pkg == "org.telegram.messenger" && !isTelegramPrivate(n.channelId)) {
                drop("Telegram: не личный чат (channel=${n.channelId})"); return
            }

            val ex = NotifResponder.extract(n)
            if (ex == null) { drop("не удалось разобрать (нет MessagingStyle/title/text)"); return }
            val channel = if (pkg == messagesPkg) Channel.MESSAGES else Channel.MESSENGER

            // ЖУРНАЛ — раньше всех правил ответа.
            //
            // Раньше история писалась внутри NotifResponder.process, то есть после проверок
            // «свежее пяти минут», «есть кнопка Ответить», «робот включён». Всё, что их не
            // проходило, не попадало в журнал вообще — а в контекст следующего ответа уходил
            // разговор с дырами, и робот отвечал так, будто переписки не было. Записывать
            // надо всегда; отвечать — по правилам ниже.
            // RCS идёт своим путём: там запись согласована с дедупом SMS↔RCS.
            // В ту же полосу, что и ответ: работа с БД не должна идти на потоке слушателя,
            // а порядок «сначала записали, потом ответили» обязан сохраниться.
            if (channel == Channel.MESSENGER)
                com.davnozdu.autoresponder.respond.EventQueue.submitMsg {
                    NotifResponder.logHistory(applicationContext, ex, tag)
                }

            // Игнорируем старые/восстановленные уведомления (после перезагрузки система
            // восстанавливает непрочитанные — на них отвечать нельзя). Только свежие.
            val ageMin = (System.currentTimeMillis() - sbn.postTime) / 60_000L
            if (System.currentTimeMillis() - sbn.postTime > s.notifMaxAgeMin * 60_000L) {
                drop("старое уведомление ($ageMin мин > ${s.notifMaxAgeMin})"); return
            }
            val history = n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            if (history != null && history.isNotEmpty()) { drop("уже отвечено из уведомления"); return }

            val sender = ex.sender
            val text = ex.text
            if (text.isBlank()) { drop("пустой текст (вложение/стикер?)"); return }
            // Служебные строки WhatsApp приходят под видом чата: ход резервного копирования,
            // «содержимое скрыто», пропущенный звонок. На пропущенный звонок робот отвечал
            // «Сейчас нерабочее время» — клиенту, который ничего не писал.
            if (NotifResponder.isServiceNotification(sender, text)) {
                drop("служебное уведомление мессенджера, не сообщение"); return
            }
            // Отсекаем Telegram-ботов (имя оканчивается на "bot").
            if (pkg == "org.telegram.messenger" && sender.trim().lowercase().endsWith("bot")) return

            val hasReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            EventLog(this).add("NOTIF[$tag] from='${sender.take(20)}' group=${ex.isGroup} reply=$hasReply text='${text.take(36)}'")
            NotifResponder.handle(this, sbn, sender, text, channel, tag, ex.isGroup, hasReply)
        } catch (e: Exception) {
            EventLog(this).add("NOTIF error: ${e.message}")
        }
    }
}
