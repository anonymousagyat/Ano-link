package com.tailconnect.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class TelephonyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (!messages.isNullOrEmpty()) {
                val sender = messages[0].displayOriginatingAddress ?: "Unknown"
                val body = messages.joinToString("") { it.displayMessageBody ?: "" }
                Log.i("TelephonyReceiver", "Received SMS from: $sender: $body")

                TailConnectService.pushSmsAlert(sender, body)
            }
        }
    }
}
