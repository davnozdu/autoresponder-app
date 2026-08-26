package com.davnozdu.autoresponder.respond

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Две независимые FIFO-полосы:
 *  - main: SMS + звонки (по порядку);
 *  - msg:  мессенджеры/RCS (по порядку), отдельно, чтобы пауза дизамбигуации
 *          не блокировала обработку SMS/звонков.
 */
object EventQueue {
    private fun lane(): Channel<suspend () -> Unit> {
        val ch = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { for (job in ch) { try { job() } catch (_: Exception) {} } }
        return ch
    }
    private val main = lane()
    private val msg = lane()

    fun submit(block: suspend () -> Unit) { main.trySend(block) }
    fun submitMsg(block: suspend () -> Unit) { msg.trySend(block) }
}
