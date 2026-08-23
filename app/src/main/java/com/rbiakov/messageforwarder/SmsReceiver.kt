package com.rbiakov.messageforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Catches incoming SMS, filters by the target SIM's subId, and enqueues
 * forwarding in WorkManager. Never stores the SMS itself.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        logAllExtras(intent)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        // Multipart SMS arrives as several PDUs — join the body.
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val timestamp = messages.first().timestampMillis

        val subId = extractSubId(intent)
        val targetSubId = SimHelper.getTargetSubId(context)

        val shouldForward = when {
            // Target SIM unknown (no permission / firmware doesn't expose the number) —
            // fallback: forward everything, the screen warns about this.
            targetSubId == null -> true
            // Firmware didn't put subId into the intent — forward everything too.
            subId == null -> true
            else -> subId == targetSubId
        }

        Log.i(TAG, "SMS от $sender, subId=$subId, targetSubId=$targetSubId, forward=$shouldForward")

        if (shouldForward) {
            ForwardWorker.enqueue(context, sender = sender, body = body, timestamp = timestamp)
        }
    }

    private fun extractSubId(intent: Intent): Int? {
        val extras = intent.extras ?: return null
        // Different firmwares put subId under different keys.
        val keys = listOf(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, // android.telephony.extra.SUBSCRIPTION_INDEX
            "subscription",
            "subscription_id",
        )
        for (key in keys) {
            if (extras.containsKey(key)) {
                val value = extras.getInt(key, -1)
                if (value >= 0) return value
            }
        }
        return null
    }

    // Debugging aid: shows which key carries subId on a given device.
    private fun logAllExtras(intent: Intent) {
        val extras = intent.extras ?: return
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            Log.d(TAG, "extra: $key = ${extras.get(key)}")
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
