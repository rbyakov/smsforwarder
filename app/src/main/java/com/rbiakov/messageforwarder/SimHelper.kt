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
            Log.w(TAG, "Нет доступа к списку SIM", e)
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
        val number = if (Build.VERSION.SDK_INT >= 33) {
            try {
                sm.getPhoneNumber(info.subscriptionId)
            } catch (e: SecurityException) {
                ""
            }
        } else {
            info.number.orEmpty()
        }
        // Normalize: we compare by suffix, so stripping spaces and dashes is enough.
        return number.filter { it.isDigit() || it == '+' }
    }
}
