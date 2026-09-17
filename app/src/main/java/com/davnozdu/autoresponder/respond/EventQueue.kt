package com.davnozdu.autoresponder.respond

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.store.RuntimeDb
import com.davnozdu.autoresponder.notif.NotifListenerService
import com.davnozdu.autoresponder.notif.NotifResponder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest

/** Durable work, three independent customers, FIFO for each verified identity. */
object EventQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()
    private val active = mutableSetOf<String>()
    @Volatile private var started = false
    // Short history imports only. Network calls never run in this lane.
    private val history = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init { scope.launch { for (job in history) try { job() } catch (e: Exception) { if (e is CancellationException) throw e } } }
    fun submitMsg(block: suspend () -> Unit) { history.trySend(block) }
    fun submit(block: suspend () -> Unit) { scope.launch { block() } }

    @Synchronized fun start(context: Context) {
        if (started) return
        RuntimeDb.get(context).recover()
        started = true
        kick(context)
    }
    fun token(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    fun enqueue(context: Context, who: String, kind: String, payload: JSONObject, token: String, ttl: Long) {
        val app = context.applicationContext
        start(app)
        RuntimeDb.get(app).enqueue(token, who, kind, payload.toString(), ttl)
        kick(app)
    }
    fun kick(context: Context) {
        val app = context.applicationContext
        scope.launch {
            scheduling.withLock {
                val db = RuntimeDb.get(app)
                while (active.size < 3) {
                    val job = db.next(active, NotifListenerService.isConnected) ?: break
                    active.add(job.who)
                    scope.launch {
                        try {
                            val p = JSONObject(job.payload)
                            if (job.kind == "notification") NotifResponder.replay(app, p, job.id, job.created)
                            else Responder.process(app, p.optString("number"), p.optString("text").ifBlank { null },
                                Kind.valueOf(job.kind), p.optInt("subId", -1), job.id, job.created)
                            db.finish(job.id)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            db.state(job.id, "failed", "${e.javaClass.simpleName}: ${e.message}")
                            EventLog(app).add("QUEUE #${job.id}: ${e.javaClass.simpleName}: ${e.message}")
                        } finally {
                            scheduling.withLock { active.remove(job.who) }
                            kick(app)
                        }
                    }
                }
            }
        }
    }
    fun beforeSend(context: Context, job: Long): Boolean {
        val db = RuntimeDb.get(context)
        if (!db.valid(job)) { db.state(job, "expired", "Ответ устарел во время подготовки"); return false }
        db.state(job, "sending")
        return true
    }
}
