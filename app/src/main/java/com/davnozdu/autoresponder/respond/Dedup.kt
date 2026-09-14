package com.davnozdu.autoresponder.respond

import java.util.concurrent.ConcurrentHashMap

/** Анти-дубль между путями SMS и уведомлений (в пределах процесса). */
object Dedup {
    private val seen = ConcurrentHashMap<String, Long>()
    private const val TTL = 90_000L

    // Схлопывание пробелов: SMS-путь и путь уведомления дают один и тот же текст с разной
    // разбивкой пробелов/переводов строк — без нормализации ключи расходятся и дубль проходит.
    private val WS = Regex("\\s+")

    /** true, если этот текст ещё не обрабатывался в окне TTL (и помечает его). */
    @Synchronized
    fun claim(text: String?): Boolean {
        // Не сворачиваем строку в 32-битный hashCode: разные сообщения с одинаковым
        // Java-хешем (например, «Aa» и «BB») иначе ошибочно считались бы дублем.
        val key = (text ?: "").replace(WS, " ").trim().lowercase()
        val now = System.currentTimeMillis()
        // очистка старого
        seen.entries.removeAll { now - it.value > TTL }
        val prev = seen[key]
        if (prev != null && now - prev < TTL) return false
        seen[key] = now
        return true
    }
}
