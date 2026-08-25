package com.davnozdu.autoresponder.respond

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Единая FIFO-очередь: входящие (SMS/RCS/WA/TG/звонки) обрабатываются по порядку. */
object EventQueue {
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (job in channel) {
                try { job() } catch (_: Exception) {}
            }
        }
    }

    fun submit(block: suspend () -> Unit) { channel.trySend(block) }
}
