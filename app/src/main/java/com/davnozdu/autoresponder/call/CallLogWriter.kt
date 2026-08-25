package com.davnozdu.autoresponder.call

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog

/** После отклонения звонка через скрининг он пишется как BLOCKED (скрыт).
 *  Пишем видимую запись MISSED (пропущенный), удалив свежую BLOCKED. Нужен WRITE_CALL_LOG. */
object CallLogWriter {
    fun writeMissed(context: Context, number: String?) {
        if (number.isNullOrBlank()) return
        try {
            val cr = context.contentResolver
            // удалить свежую BLOCKED (type=6) по этому номеру за последние 20с
            cr.delete(CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER}=? AND ${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>?",
                arrayOf(number, CallLog.Calls.BLOCKED_TYPE.toString(),
                    (System.currentTimeMillis() - 20_000).toString()))
            val cv = ContentValues().apply {
                put(CallLog.Calls.NUMBER, number)
                put(CallLog.Calls.TYPE, CallLog.Calls.MISSED_TYPE)
                put(CallLog.Calls.DATE, System.currentTimeMillis())
                put(CallLog.Calls.DURATION, 0)
                put(CallLog.Calls.NEW, 1)
            }
            cr.insert(CallLog.Calls.CONTENT_URI, cv)
        } catch (e: Exception) { /* нет WRITE_CALL_LOG или OEM ограничение */ }
    }
}
