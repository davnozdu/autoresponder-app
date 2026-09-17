package com.davnozdu.autoresponder.debug

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.davnozdu.autoresponder.store.*
import com.davnozdu.autoresponder.respond.Outgoing
import java.io.File

/** Runs via app_process against isolated fixtures, never the installed app's data. */
object ReliabilityChecks {
    @JvmStatic fun main(args: Array<String>) {
        try {
            if(android.os.Looper.getMainLooper()==null) android.os.Looper.prepareMainLooper()
            val type=Class.forName("android.app.ActivityThread")
            val thread=type.getMethod("systemMain").invoke(null)
            val systemContext=type.getMethod("getSystemContext").invoke(thread) as Context
            val root=File(args.single()).apply { mkdirs() }
            val context=object: ContextWrapper(systemContext) {
                override fun getApplicationContext(): Context = this
                override fun getDatabasePath(name: String) = File(root,name)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
                override fun getSystemService(name: String): Any? = null
                override fun getSystemServiceName(serviceClass: Class<*>): String? = if(serviceClass==android.app.NotificationManager::class.java) Context.NOTIFICATION_SERVICE else null
            }
            val params=SQLiteDatabase.OpenParams.Builder().addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY)
                .setJournalMode("DELETE").setSynchronousMode("FULL").build()
            val runtime=RuntimeDb.get(context)
            runtime.setOpenParams(params)
            val a=runtime.enqueue("a","alice","SMS","{}",60000)
            val a2=runtime.enqueue("a2","alice","SMS","{}",60000)
            val b=runtime.enqueue("b","bob","SMS","{}",60000)
            check(a>0 && runtime.enqueue("a","alice","SMS","{}",60000)==-1L)
            check(runtime.next(emptySet(),true)?.id==a)
            check(runtime.next(setOf("alice"),true)?.id==b)
            runtime.state(a,"sending")
            runtime.recover()
            check(runtime.next(emptySet(),true)?.id==a2) // sending is not replayed
            runtime.setControl("alice",Long.MAX_VALUE,123)
            runtime.bind("telegram:alice","alice")
            check(runtime.canonical("telegram:alice")=="alice")
            check(runtime.control("alice")?.until==Long.MAX_VALUE)
            println("PASS durable FIFO, dedup, independent customers, recovery, handoff")

            val history=HistoryDb(context,"fixture.db")
            history.setOpenParams(params)
            history.insert("test",null,"sms","in","before snapshot",1)
            val snapshot=File(root,"snapshot.db")
            Backup.snapshotDatabase(history.writableDatabase,snapshot)
            history.insert("test",null,"sms","out","after snapshot",2)
            Backup.restoreTables(history.writableDatabase,snapshot)
            check(history.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {it.moveToFirst();it.getInt(0)}==1)
            val broken=File(root,"broken.db").apply { writeText("not a database") }
            check(runCatching {Backup.validate(broken)}.isFailure)
            check(history.readableDatabase.rawQuery("PRAGMA integrity_check",null).use {it.moveToFirst();it.getString(0)}=="ok")
            println("PASS consistent snapshot, transactional restore, corruption rejection")

            val out=Outgoing.create(context,"test","sms","parts",2,0)
            Outgoing.acknowledgement(context,out,0,-1,false)
            fun state(id:String)=runtime.readableDatabase.rawQuery("SELECT state FROM outgoing WHERE id=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)}
            check(state(out)=="submitted")
            Outgoing.acknowledgement(context,out,1,-1,false)
            Outgoing.acknowledgement(context,out,1,-1,false)
            check(state(out)=="sent")
            Outgoing.acknowledgement(context,out,0,-1,true)
            Outgoing.acknowledgement(context,out,1,-1,true)
            check(state(out)=="delivered")
            val partial=Outgoing.create(context,"test","sms","partial",2,0)
            Outgoing.acknowledgement(context,partial,0,-1,false)
            Outgoing.acknowledgement(context,partial,1,1,false)
            check(state(partial)=="failed")
            println("PASS segmented SMS acknowledgement, duplicate callback, delivery, partial failure")
            history.close(); runtime.close()
            println("ALL DEVICE CHECKS PASSED")
        } catch(t:Throwable) { t.printStackTrace(); kotlin.system.exitProcess(1) }
        kotlin.system.exitProcess(0)
    }
}
