package com.davnozdu.autoresponder.msgrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.davnozdu.autoresponder.data.LogFile

/** Promotes the app to foreground before opening the microphone; active only during a VoIP call. */
class MsgrRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Запись звонка в мессенджере",
            NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Запись звонка в мессенджере")
            .setContentText("Записываются обе стороны разговора")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            MsgrCaptureManager.onForegroundReady()
        } catch (e: Exception) {
            LogFile.append("msgrec: служба микрофона не запустилась: ${e.javaClass.simpleName}: ${e.message}")
            MsgrCaptureManager.onForegroundFailed()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "msgr_recording"
        const val NOTIFICATION_ID = 4207
    }
}
