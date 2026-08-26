package com.davnozdu.autoresponder.rules

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class SimInfo(val subId: Int, val slot: Int, val label: String)

object SimUtil {

    /**
     * subId SIM-карты, на которую пришёл входящий звонок (из PhoneAccountHandle звонка).
     * Нужен, чтобы отвечать SMS С ТОЙ ЖЕ карты. -1, если определить не удалось.
     */
    fun subIdFromCall(context: Context, details: android.telecom.Call.Details): Int {
        val handle = details.accountHandle ?: return -1
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return -1
            // API 30+: прямое сопоставление PhoneAccountHandle -> subscriptionId.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val id = tm.getSubscriptionId(handle)
                if (id >= 0) return id
            }
            // Фолбэк: id хэндла нередко равен строковому subId.
            handle.id?.toIntOrNull()?.takeIf { it >= 0 } ?: -1
        } catch (_: Exception) { -1 }
    }

    /** Активные SIM. Требует READ_PHONE_STATE. */
    fun activeSims(context: Context): List<SimInfo> {
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            @Suppress("MissingPermission")
            val list = sm.activeSubscriptionInfoList ?: return emptyList()
            list.map {
                val name = it.displayName?.toString()?.ifBlank { null }
                    ?: it.carrierName?.toString()?.ifBlank { null } ?: "SIM"
                SimInfo(it.subscriptionId, it.simSlotIndex, "SIM ${it.simSlotIndex + 1}: $name")
            }.sortedBy { it.slot }
        } catch (e: Exception) { emptyList() }
    }

    /** subId для слота (0=SIM1,1=SIM2); -1 если не найден. */
    fun subIdForSlot(context: Context, slot: Int): Int =
        activeSims(context).firstOrNull { it.slot == slot }?.subId ?: -1

    /**
     * Итоговый subId для отправки по настройке слота.
     * slotPref: -1 = системная по умолчанию; 0/1 = SIM1/SIM2.
     * Если выбранной SIM нет — откат на системную (-1).
     */
    fun resolveSubId(context: Context, slotPref: Int): Int {
        if (slotPref < 0) return -1
        val sub = subIdForSlot(context, slotPref)
        return if (sub >= 0) sub else -1
    }
}
