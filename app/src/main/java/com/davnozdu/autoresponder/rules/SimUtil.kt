package com.davnozdu.autoresponder.rules

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * @param name   имя карты по системе («T-Mobile», «Vodafone»), без номера слота
 * @param label  готовая подпись со слотом («SIM 1: T-Mobile») — для списков и журнала
 */
data class SimInfo(val subId: Int, val slot: Int, val name: String) {
    val label: String get() = "SIM ${slot + 1}: $name"
}

object SimUtil {

    /**
     * subId SIM-карты, на которую пришёл входящий звонок (из PhoneAccountHandle звонка).
     * Нужен, чтобы отвечать SMS С ТОЙ ЖЕ карты. -1, если определить не удалось.
     */
    fun subIdFromCall(context: Context, details: android.telecom.Call.Details): Int {
        val handle = details.accountHandle ?: return -1
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            // API 30+: прямое сопоставление PhoneAccountHandle -> subscriptionId.
            if (tm != null && android.os.Build.VERSION.SDK_INT >= 30) {
                val id = tm.getSubscriptionId(handle)
                if (id >= 0) return id
            }
            val hid = handle.id
            // Фолбэк 1: id хэндла нередко равен строковому subId.
            hid?.toIntOrNull()?.takeIf { it >= 0 }?.let { return it }
            // Фолбэк 2: на части прошивок (в т.ч. OxygenOS) id хэндла — это ICCID карты.
            if (!hid.isNullOrBlank()) {
                subByIccId(context, hid)?.let { return it }
            }
            -1
        } catch (_: Exception) { -1 }
    }

    /** subId по ICCID (id PhoneAccountHandle на некоторых прошивках). */
    private fun subByIccId(context: Context, iccId: String): Int? = try {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        @Suppress("MissingPermission")
        sm?.activeSubscriptionInfoList?.firstOrNull {
            val icc = it.iccId ?: ""
            icc.isNotBlank() && (icc == iccId || icc.startsWith(iccId) || iccId.startsWith(icc))
        }?.subscriptionId
    } catch (_: Exception) { null }

    /** Строка для журнала: какие SIM активны и что известно про них. */
    fun describe(context: Context): String {
        val sims = activeSims(context)
        if (sims.isEmpty()) return "SIM не определены"
        return sims.joinToString("; ") { "slot=${it.slot} subId=${it.subId} ${it.label}" }
    }

    // Список карт меняется редко (вставили карту, переключили eSIM), а спрашивается на каждое
    // событие: при выборе SIM для ответа, в диагностике и при разборе PhoneAccountHandle.
    // Держим короткий кэш; при сбое отправки он сбрасывается принудительно (invalidate).
    private const val SIM_TTL_MS = 60_000L
    @Volatile private var simCache: Pair<Long, List<SimInfo>>? = null

    /** Сбросить кэш карт — например, если отправка не удалась и subId мог устареть. */
    fun invalidate() { simCache = null }

    /** Активные SIM. Требует READ_PHONE_STATE. */
    fun activeSims(context: Context): List<SimInfo> {
        simCache?.let { (ts, v) ->
            if (System.currentTimeMillis() - ts < SIM_TTL_MS && v.isNotEmpty()) return v
        }
        val fresh = readSims(context)
        // Пустой список не кэшируем: это чаще «нет разрешения / провайдер не готов», чем факт.
        if (fresh.isNotEmpty()) simCache = System.currentTimeMillis() to fresh
        return fresh
    }

    private fun readSims(context: Context): List<SimInfo> {
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            @Suppress("MissingPermission")
            val list = sm.activeSubscriptionInfoList ?: return emptyList()
            list.map {
                val display = it.displayName?.toString()?.trim()?.ifBlank { null }
                val carrier = it.carrierName?.toString()?.trim()?.ifBlank { null }
                SimInfo(it.subscriptionId, it.simSlotIndex, simName(display, carrier))
            }.sortedBy { it.slot }
        } catch (e: Exception) { emptyList() }
    }

    // Прошивки часто оставляют displayName заглушкой вида «SIM1»/«Card 2» — она ничего не
    // говорит о карте, поэтому в таком случае показываем оператора.
    private val PLACEHOLDER = Regex("^(sim|card|слот|slot)\\s*[0-9]?$", RegexOption.IGNORE_CASE)

    private fun simName(display: String?, carrier: String?): String {
        val meaningful = display?.takeUnless { PLACEHOLDER.matches(it) }
        return when {
            meaningful == null -> carrier ?: display ?: "SIM"
            carrier == null || carrier.equals(meaningful, true) -> meaningful
            else -> "$meaningful ($carrier)"
        }
    }

    /** subId для слота (0=SIM1,1=SIM2); -1 если не найден. */
    fun subIdForSlot(context: Context, slot: Int): Int =
        activeSims(context).firstOrNull { it.slot == slot }?.subId ?: -1

    /**
     * Итоговый subId для отправки по номеру слота (0 = SIM1, 1 = SIM2).
     * Если такой карты нет (вынута/выключена) — -1, отправка уйдёт системной по умолчанию.
     */
    fun resolveSubId(context: Context, slot: Int): Int {
        if (slot < 0) return -1
        val sub = subIdForSlot(context, slot)
        return if (sub >= 0) sub else -1
    }
}
