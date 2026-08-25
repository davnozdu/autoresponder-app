package com.davnozdu.autoresponder.respond

import java.util.concurrent.ConcurrentHashMap

/** Анти-дубль между путями SMS и уведомлений (в пределах процесса). */
object Dedup {
    private val seen = ConcurrentHashMap<String, Long>()
    private const val TTL = 90_000L

    /** true, если этот текст ещё не обрабатывался в окне TTL (и помечает его). */
    @Synchronized
    fun claim(text: String?): Boolean {
        val key = (text ?: "").trim().lowercase().hashCode().toString()
        val now = System.currentTimeMillis()
        // очистка старого
        seen.entries.removeAll { now - it.value > TTL }
        val prev = seen[key]
        if (prev != null && now - prev < TTL) return false
        seen[key] = now
        return true
    }
}
