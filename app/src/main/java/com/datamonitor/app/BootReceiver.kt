package com.datamonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = Prefs(context)
            if (prefs.monitoringEnabled) {
                ContextCompat.startForegroundService(
                    context, Intent(context, DataMonitorService::class.java)
                )
            }
        }
    }
}
