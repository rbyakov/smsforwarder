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
 * Finds the subId of the target SIM by the number's trailing digits
 * (Config.targetSimSuffix). Requires READ_PHONE_STATE.
 */
object SimHelper {
    private const val TAG = "SimHelper"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun getSimState(context: Context): SimState {
        if (!hasPermission(context)) return SimState(hasPermission = false, sims = emptyList())

        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val infos: List<SubscriptionInfo> = try {
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to the SIM list", e)
            return SimState(hasPermission = false, sims = emptyList())
        }

        val suffix = Config.targetSimSuffix
        val sims = infos.map { info ->
            val number = phoneNumber(sm, info)
            SimCard(
                subId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                carrier = info.carrierName?.toString().orEmpty(),
                number = number,
                isTarget = suffix.isNotBlank() && number.isNotBlank() && number.endsWith(suffix),
            )
        }
        return SimState(hasPermission = true, sims = sims)
    }

    /** subId of the target SIM, or null if not found (no permission / number unavailable). */
    fun getTargetSubId(context: Context): Int? = getSimState(context).targetSubId

    @Suppress("DEPRECATION")
    private fun phoneNumber(sm: SubscriptionManager, info: SubscriptionInfo): String {
        val subId = info.subscriptionId
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            // The SIM often doesn't store the MSISDN, so getPhoneNumber(subId) can be
            // empty. Try every source explicitly: carrier -> UICC (SIM) -> IMS (VoLTE).
            val sources = listOf(
                "carrier" to SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER,
                "uicc" to SubscriptionManager.PHONE_NUMBER_SOURCE_UICC,
                "ims" to SubscriptionManager.PHONE_NUMBER_SOURCE_IMS,
            )
            var found = ""
            for ((name, source) in sources) {
                val value = try {
                    sm.getPhoneNumber(subId, source)
                } catch (e: SecurityException) {
                    ""
                }
                Log.d(TAG, "subId=$subId source=$name -> ${if (value.isBlank()) "(empty)" else "present"}")
                if (found.isBlank() && value.isNotBlank()) found = value
            }
            found
        } else {
            info.number.orEmpty()
        }
        Log.d(TAG, "subId=$subId resolved number ${if (raw.isBlank()) "NOT found" else "found"}")
        // Normalize: we compare by suffix, so stripping spaces and dashes is enough.
        return raw.filter { it.isDigit() || it == '+' }
    }
}
