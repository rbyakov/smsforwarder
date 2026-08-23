package com.rbiakov.messageforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

data class SimCard(
    val subId: Int,
    val slotIndex: Int,
    val carrier: String,
    val number: String,
    val isTarget: Boolean,
)

data class SimState(
    val hasPermission: Boolean,
    val sims: List<SimCard>,
) {
    val targetSubId: Int? get() = sims.firstOrNull { it.isTarget }?.subId
    val targetFound: Boolean get() = targetSubId != null
}

/**
 * Identifies the target SIM by the physical slot the user picked once (stored
 * in prefs). Matching is by slot, not by phone number, so it does not depend on
 * VoLTE/IMS or on whether the carrier wrote the number onto the SIM. The number
 * is resolved only for display. Requires READ_PHONE_STATE (+ READ_PHONE_NUMBERS
 * for the number).
 */
object SimHelper {
    private const val TAG = "SimHelper"
    private const val PREFS = "forwarder"
    private const val KEY_TARGET_SLOT = "target_slot"
    private const val NO_SLOT = -1

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /** Physical slot the user chose as the target, or -1 if none. */
    fun storedTargetSlot(context: Context): Int =
        prefs(context).getInt(KEY_TARGET_SLOT, NO_SLOT)

    /** Remember which SIM to forward, by its physical slot. */
    fun setTargetSlot(context: Context, slotIndex: Int) {
        prefs(context).edit().putInt(KEY_TARGET_SLOT, slotIndex).apply()
    }

    fun getSimState(context: Context): SimState {
        if (!hasPermission(context)) return SimState(hasPermission = false, sims = emptyList())

        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val infos: List<SubscriptionInfo> = try {
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to the SIM list", e)
            return SimState(hasPermission = false, sims = emptyList())
        }

        val targetSlot = storedTargetSlot(context)
        val sims = infos.map { info ->
            SimCard(
                subId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                carrier = info.carrierName?.toString().orEmpty(),
                number = phoneNumber(sm, info),
                isTarget = info.simSlotIndex == targetSlot,
            )
        }
        return SimState(hasPermission = true, sims = sims)
    }

    /** subId of the currently active SIM in the chosen slot, or null if none. */
    fun getTargetSubId(context: Context): Int? = getSimState(context).targetSubId

    @Suppress("DEPRECATION")
    private fun phoneNumber(sm: SubscriptionManager, info: SubscriptionInfo): String {
        val subId = info.subscriptionId
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            // The SIM often doesn't store the MSISDN, so getPhoneNumber(subId) can be
            // empty. Try every source explicitly: carrier -> UICC (SIM) -> IMS (VoLTE).
            val sources = listOf(
                SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER,
                SubscriptionManager.PHONE_NUMBER_SOURCE_UICC,
                SubscriptionManager.PHONE_NUMBER_SOURCE_IMS,
            )
            sources.firstNotNullOfOrNull { source ->
                try {
                    sm.getPhoneNumber(subId, source).takeIf { it.isNotBlank() }
                } catch (e: SecurityException) {
                    null
                }
            }.orEmpty()
        } else {
            info.number.orEmpty()
        }
        // Normalize: strip spaces and dashes, keep digits and '+'.
        return raw.filter { it.isDigit() || it == '+' }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
