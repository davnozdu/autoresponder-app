package com.davnozdu.autoresponder.rules

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Определение текущего аудио-маршрута — нужно только для [HeadsetPolicy]. */
object AudioRouteUtil {

    /** Подключена ли сейчас Bluetooth-гарнитура (наушники/колонка), независимо от звонка.
     *  Не требует рантайм-разрешений — список выходных устройств доступен всем. */
    fun isBluetoothHeadsetActive(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }
}
