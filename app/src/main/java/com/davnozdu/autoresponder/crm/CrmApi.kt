package com.davnozdu.autoresponder.crm

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.llm.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Активная запись клиента: заказ или рекламация в том виде, в каком её можно назвать вслух. */
data class CrmRecord(
    val entity: String,          // order | claim
    val id: Int,
    val number: String,          // ZK-2026-0042
    val device: String,          // «Ноутбук MacBook Pro 14», у рекламации пусто
    val label: String,           // «В работе»
    val lastLabel: String?,      // «Диагностика» — последняя запись ленты этапов
    val lastAt: String?,         // когда
    val deadline: String?,       // срок рекламации
    val price: Double?,          // только если CRM разрешила
    val canAsk: Boolean          // принимает ли запись сообщения
)

data class CrmLookup(val found: Boolean, val lang: String, val records: List<CrmRecord>)

/**
 * Запросы к CRM мастерской.
 *
 * Таймауты короткие и отдельные от LLM: ответ клиенту не должен ждать CRM. Любая
 * ошибка — это null, а не исключение: не ответили — отвечаем клиенту как обычно.
 */
object CrmApi {

    private val client = Http.client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun url(s: Settings, route: String) = "${s.crmBaseUrl}/index.php?r=$route"

    /**
     * Токен уходит двумя заголовками сразу.
     *
     * Под CGI/FastCGI Apache не передаёт PHP заголовок Authorization без отдельного
     * rewrite, и Bearer молча теряется; X-Api-Token доходит всегда. Один запрос вместо
     * двух попыток и разбирательств «почему 404».
     */
    private fun Request.Builder.auth(s: Settings) = this
        .header("Authorization", "Bearer ${s.crmToken}")
        .header("X-Api-Token", s.crmToken)

    /**
     * Реестр номеров с активными записями.
     *
     * @return null при ошибке, RosterResult.NotModified если CRM ответила 304.
     */
    fun roster(context: Context, etag: String): RosterResult {
        val s = Settings(context)
        if (!s.crmReady) return RosterResult.Error("не настроено")
        return try {
            val req = Request.Builder().url(url(s, "bot.roster")).auth(s)
                .apply { if (etag.isNotBlank()) header("If-None-Match", etag) }
                .get().build()
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 304 -> RosterResult.NotModified
                    !r.isSuccessful -> RosterResult.Error("HTTP ${r.code}")
                    else -> {
                        val body = r.body?.string().orEmpty()
                        val arr = JSONObject(body).optJSONArray("phones") ?: JSONArray()
                        val list = ArrayList<String>(arr.length())
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        RosterResult.Ok(list, r.header("ETag").orEmpty())
                    }
                }
            }
        } catch (e: Exception) {
            RosterResult.Error(e.javaClass.simpleName + ": " + e.message)
        }
    }

    /** Что в работе у этого номера. null — не спросили (не настроено, сеть, ошибка). */
    fun lookup(context: Context, phone: String): CrmLookup? {
        val s = Settings(context)
        if (!s.crmReady) return null
        val payload = JSONObject().put("phone", phone).toString()
        return try {
            val req = Request.Builder().url(url(s, "bot.lookup")).auth(s)
                .post(payload.toRequestBody(JSON)).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    EventLog(context).add("CRM lookup: HTTP ${r.code}")
                    return null
                }
                val o = JSONObject(r.body?.string().orEmpty())
                if (!o.optBoolean("found", false)) return CrmLookup(false, "", emptyList())
                val arr = o.optJSONArray("records") ?: JSONArray()
                val out = ArrayList<CrmRecord>(arr.length())
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    val last = j.optJSONObject("last")
                    out.add(CrmRecord(
                        entity = j.optString("entity", "order"),
                        id = j.optInt("id"),
                        number = j.optString("number"),
                        device = j.optString("device"),
                        label = j.optString("label"),
                        lastLabel = last?.optString("label")?.ifBlank { null },
                        lastAt = last?.optString("at")?.ifBlank { null },
                        deadline = j.optString("deadline").ifBlank { null },
                        price = if (j.has("price")) j.optDouble("price") else null,
                        canAsk = j.optBoolean("can_ask", false)
                    ))
                }
                CrmLookup(true, o.optString("lang", ""), out)
            }
        } catch (e: Exception) {
            EventLog(context).add("CRM lookup ошибка: ${e.message}")
            null
        }
    }

    /** Вопрос мастеру. Возвращает null при успехе, иначе код причины от CRM. */
    fun ask(context: Context, phone: String, entity: String, id: Int,
            text: String, channel: String): String? {
        val s = Settings(context)
        if (!s.crmReady) return "не настроено"
        val payload = JSONObject()
            .put("phone", phone).put("entity", entity).put("id", id)
            .put("text", text).put("channel", channel).toString()
        return try {
            val req = Request.Builder().url(url(s, "bot.ask")).auth(s)
                .post(payload.toRequestBody(JSON)).build()
            client.newCall(req).execute().use { r ->
                val o = try { JSONObject(r.body?.string().orEmpty()) } catch (_: Exception) { JSONObject() }
                if (o.optBoolean("ok", false)) null else o.optString("error").ifBlank { "HTTP ${r.code}" }
            }
        } catch (e: Exception) {
            e.message ?: "ошибка сети"
        }
    }
}

sealed class RosterResult {
    data class Ok(val phones: List<String>, val etag: String) : RosterResult()
    object NotModified : RosterResult()
    data class Error(val why: String) : RosterResult()
}
