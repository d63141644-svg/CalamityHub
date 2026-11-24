package com.calamity.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val always = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
            .getBoolean("always", false)
        if (always) {
            context.startForegroundService(Intent(context, TurboService::class.java))
        }
    }
}
