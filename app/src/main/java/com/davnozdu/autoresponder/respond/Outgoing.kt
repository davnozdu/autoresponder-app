package com.davnozdu.autoresponder.respond

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.ReplyStore
import com.davnozdu.autoresponder.notif.DndStats
import com.davnozdu.autoresponder.store.HistoryLogger
import com.davnozdu.autoresponder.store.RuntimeDb
import java.util.UUID

object Outgoing {
    @Synchronized fun create(context: Context, who: String, channel: String, text: String, parts: Int,
                            job: Long, historyChannel: String = "", limitKey: String = "", timeout: Int = 1): String {
        val db = RuntimeDb.get(context).writableDatabase
        val id = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            db.insertOrThrow("outgoing", null, ContentValues().apply {
                put("id", id); put("identity", who); put("channel", channel); put("body", text)
                put("created", System.currentTimeMillis()); put("state", "submitted"); put("detail", "")
                put("parts", parts); put("job", job); put("history_key", historyChannel)
                put("limit_key", limitKey); put("timeout", timeout)
            })
            repeat(parts) { n -> db.execSQL("INSERT INTO segments(outgoing,part,sent,delivered) VALUES(?,?,0,0)", arrayOf(id,n)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }
    fun startRemote(context: Context, who: String, channel: String, text: String, job: Long) = create(context, who, channel, text, 0, job)
    @Synchronized fun remoteResult(context: Context, id: String, ok: Boolean) {
        update(context, id, if (ok) "submitted" else "failed", if (ok) "Передано кнопке Reply; доставка не подтверждена" else "Кнопка Reply недоступна")
    }
    @Synchronized fun observed(context: Context, who: String, channel: String, text: String, at: Long) {
        val db=RuntimeDb.get(context)
        val matches=mutableListOf<String>()
        db.readableDatabase.rawQuery("SELECT id,identity FROM outgoing WHERE channel=? AND body=? AND state='submitted' AND ABS(created-?) < 600000",
            arrayOf(channel,text,at.toString())).use { c ->
            while(c.moveToNext()) if(Handoff.key(context,c.getString(1),channel)==Handoff.key(context,who,channel)) matches.add(c.getString(0))
        }
        matches.forEach { update(context,it,"observed","Подтверждено импортом переписки") }
    }
    @Synchronized fun failed(context: Context, id: String, detail: String) { update(context,id,"failed",detail); alert(context, id, detail) }
    private fun update(context: Context, id: String, state: String, detail: String) {
        val db = RuntimeDb.get(context)
        db.writableDatabase.execSQL("UPDATE outgoing SET state=?,detail=? WHERE id=?", arrayOf(state,detail,id))
        db.readableDatabase.rawQuery("SELECT job FROM outgoing WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) db.state(it.getLong(0), state, detail) }
    }
    @Synchronized fun pending(context: Context, who: String): Boolean = RuntimeDb.get(context).readableDatabase.rawQuery(
        "SELECT 1 FROM outgoing WHERE limit_key=? AND state='submitted' AND created>? LIMIT 1", arrayOf(who,(System.currentTimeMillis()-15*60_000L).toString())).use { it.moveToFirst() }

    @Synchronized fun acknowledgement(context: Context, id: String, part: Int, result: Int, delivery: Boolean) {
        val db = RuntimeDb.get(context).writableDatabase
        val col = if (delivery) "delivered" else "sent"
        // Duplicate callbacks are idempotent. Successful delivery may follow delayed sent callbacks.
        db.execSQL("UPDATE segments SET $col=? WHERE outgoing=? AND part=? AND $col=0", arrayOf(if (result == 0) 999 else result,id,part))
        var total=0; var ok=0; var bad=0; var delivered=0; var badCode=0
        db.rawQuery("SELECT sent,delivered FROM segments WHERE outgoing=?", arrayOf(id)).use { c ->
            while(c.moveToNext()) {
                total++
                if(c.getInt(0)==Activity.RESULT_OK) ok++ else if(c.getInt(0)!=0) { bad++; if(badCode==0) badCode=c.getInt(0) }
                if(c.getInt(1)==Activity.RESULT_OK) delivered++
            }
        }
        if (total == 0) return
        // Отчёт о доставке приходит ПОСЛЕ подтверждения отправки, поэтому ветка «все ушли»
        // срабатывает первой и раньше прятала недоставку под «N/N сегментов». Ответ всё равно
        // засчитываем (повтор дал бы дубль), но владельцу говорим.
        val badDelivery = delivery && result != Activity.RESULT_OK
        if (bad > 0) {
            failed(context,id,"Отправлено $ok/$total сегментов; ошибка ${if(badCode==999) 0 else badCode}. Автоповтор отключён во избежание дублей")
        } else if (delivered == total || ok == total) {
            update(context,id,if(delivered==total) "delivered" else "sent",
                if(badDelivery) "Отправлено $total/$total сегм.; доставка не подтверждена (код $result)" else "$total/$total сегментов")
            db.rawQuery("SELECT identity,body,history_key,limit_key,timeout,committed FROM outgoing WHERE id=?", arrayOf(id)).use { c ->
                if(c.moveToFirst() && c.getInt(5)==0) {
                    val historyChannel=c.getString(2); val limitKey=c.getString(3)
                    if (limitKey.isNotBlank()) {
                        ReplyStore(context).markReplied(limitKey,c.getInt(4))
                        HistoryLogger.record(context,c.getString(0),historyChannel,"out",c.getString(1),auto=true)
                        DndStats.onAutoReply(context)
                    }
                    db.execSQL("UPDATE outgoing SET committed=1 WHERE id=?", arrayOf(id))
                }
            }
            if (badDelivery) alert(context,id,"Отправлено $total/$total сегм., но оператор не подтвердил доставку (код $result)")
        } else if (badDelivery) {
            update(context,id,"sent","Отправлено; доставка не подтверждена (код $result)")
        }
    }
    private fun alert(context: Context, id: String, detail: String) {
        EventLog(context).add("SEND $id — $detail")
        val nm=context.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel("send_errors","Ошибки отправки",android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val tap=android.app.PendingIntent.getActivity(context,0,android.content.Intent(context,com.davnozdu.autoresponder.ui.StatusActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE)
        try { nm.notify(id,2101,androidx.core.app.NotificationCompat.Builder(context,"send_errors")
            .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Автоответ не отправлен полностью")
            .setContentText(detail).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(tap).setAutoCancel(true).build()) } catch (_: SecurityException) {}
    }
}
