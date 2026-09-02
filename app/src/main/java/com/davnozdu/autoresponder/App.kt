package com.davnozdu.autoresponder

import android.app.Application
import com.davnozdu.autoresponder.data.LogFile
import com.davnozdu.autoresponder.data.Settings

/**
 * Точка старта процесса — что бы его ни подняло: экран настроек, приём SMS,
 * screening звонка или слушатель уведомлений.
 *
 * Здесь только то, что должно быть верным ДО первого события, иначе первая же
 * SMS после перезагрузки обработается с настройками по умолчанию.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val s = Settings(this)
        LogFile.keepDays = s.logKeepDays
        LogFile.enabled = s.logToFile
        LogFile.rotate()
        // Признак жизни для KernelSU-модуля: без него «приложение убито менеджером
        // питания» выглядит снаружи ровно как «всё настроено и работает».
        com.davnozdu.autoresponder.store.Heartbeat.tick(this)
    }
}
