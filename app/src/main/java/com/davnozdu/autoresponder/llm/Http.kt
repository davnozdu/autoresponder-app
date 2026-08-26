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
    const val JSON = "application/json"
}
