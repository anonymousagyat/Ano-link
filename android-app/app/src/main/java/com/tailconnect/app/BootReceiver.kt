package com.tailconnect.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i(TAG, "Device booted. Starting TailConnectService daemon...")
            val serviceIntent = Intent(context, TailConnectService::class.java)
            try {
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "TailConnectService successfully started on boot.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TailConnectService on boot: ${e.message}")
            }
        }
    }
}
