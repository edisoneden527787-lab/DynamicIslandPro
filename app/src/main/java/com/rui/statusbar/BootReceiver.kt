package com.rui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("rui_statusbar", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hideNativeStatusBar", false)) return
        if (!Settings.canDrawOverlays(context)) return

        try {
            val serviceIntent = Intent(context, StatusBarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i("RuiStatusBar", "Boot restore service started; waiting for Shizuku")
        } catch (e: Exception) {
            Log.e("RuiStatusBar", "Unable to start boot restore service", e)
        }
    }
}