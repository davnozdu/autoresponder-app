package com.davnozdu.autoresponder.rules

/** Решение «форсировать голосовой автоответчик из-за подключённой Bluetooth-гарнитуры».
 *  Чистая логика без Android-зависимостей — определение самой гарнитуры в [AudioRouteUtil]. */
object HeadsetPolicy {

    fun shouldForceAnswer(enabled: Boolean, headsetConnected: Boolean, skip: Boolean): Boolean =
        enabled && headsetConnected && !skip
}
