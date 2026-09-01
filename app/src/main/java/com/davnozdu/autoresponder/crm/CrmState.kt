package com.davnozdu.autoresponder.crm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Состояние разговора о заказе — на человека, ненадолго.
 *
 * Нужно двум шагам: «про какой заказ вы спрашиваете?» и «напишите ДА, если нужен
 * мастер». Живёт в SharedPreferences, а не в памяти: процесс приложения система
 * убивает между сообщениями, а клиент отвечает «ДА» через минуту.
 *
 * Отдельный файл настроек, чтобы не мешаться с основными и чиститься целиком.
 */
object CrmState {

    private const val TTL_MS = 30 * 60_000L
    private const val MAX_RETRY = 1

    enum class Kind { CHOICE, ASK }

    data class Entry(
        val kind: Kind,
        val records: List<CrmRecord>,   // для CHOICE — кандидаты, для ASK — одна запись
        val question: String,           // исходный вопрос клиента: он и уйдёт мастеру
        val retries: Int,
        val at: Long
    )

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("autoresp_crm", Context.MODE_PRIVATE)

    fun get(context: Context, key: String): Entry? {
        val raw = sp(context).getString(key, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val at = o.optLong("at")
            if (System.currentTimeMillis() - at > TTL_MS) { clear(context, key); return null }
            val arr = o.optJSONArray("recs") ?: JSONArray()
            val recs = ArrayList<CrmRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                recs.add(CrmRecord(
                    entity = j.optString("e"), id = j.optInt("i"), number = j.optString("n"),
                    device = j.optString("d"), label = j.optString("l"),
                    lastLabel = j.optString("ll").ifBlank { null },
                    lastAt = j.optString("la").ifBlank { null },
                    deadline = j.optString("dl").ifBlank { null },
                    price = null, canAsk = j.optBoolean("a", true)))
            }
            Entry(Kind.valueOf(o.optString("k")), recs, o.optString("q"), o.optInt("r"), at)
        } catch (_: Exception) { clear(context, key); null }
    }

    fun put(context: Context, key: String, kind: Kind, records: List<CrmRecord>,
            question: String, retries: Int = 0) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject()
                .put("e", r.entity).put("i", r.id).put("n", r.number).put("d", r.device)
                .put("l", r.label).put("ll", r.lastLabel ?: "").put("la", r.lastAt ?: "")
                .put("dl", r.deadline ?: "").put("a", r.canAsk))
        }
        val o = JSONObject()
            .put("k", kind.name).put("recs", arr).put("q", question)
            .put("r", retries).put("at", System.currentTimeMillis())
        sp(context).edit().putString(key, o.toString()).apply()
        prune(context)
    }

    fun clear(context: Context, key: String) = sp(context).edit().remove(key).apply()

    /** Протухшие записи чистим при каждой записи: их единицы, разрастись не должно. */
    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        val p = sp(context)
        val dead = p.all.keys.filter { k ->
            val v = p.getString(k, null) ?: return@filter true
            try { now - JSONObject(v).optLong("at") > TTL_MS } catch (_: Exception) { true }
        }
        if (dead.isNotEmpty()) p.edit().apply { dead.forEach { remove(it) } }.apply()
    }
}
