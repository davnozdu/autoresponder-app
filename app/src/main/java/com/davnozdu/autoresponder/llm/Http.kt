package com.davnozdu.autoresponder.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object Http {
    // callTimeout — жёсткий потолок на ВЕСЬ вызов (connect+write+read). Если основной канал
    // «тупит» дольше ~13с, запрос обрывается и Llm.generate переключается на резервный;
    // если и он молчит — отдаётся стандартная заглушка из настроек.
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(13, TimeUnit.SECONDS)
        .build()

    // Режим размышления (reasoning): модель думает дольше — даём большой таймаут.
    // Общий пул соединений с основным клиентом.
    val clientThink: OkHttpClient = client.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(95, TimeUnit.SECONDS)
        .build()

    /** Клиент под режим: обычный (быстрый фолбэк) или «думающий» (длинный таймаут). */
    fun client(think: Boolean): OkHttpClient = if (think) clientThink else client

    const val JSON = "application/json"
}
