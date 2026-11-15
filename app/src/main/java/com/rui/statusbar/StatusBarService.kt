package com.rui.statusbar

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusBarService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.rui.statusbar.REFRESH"
        private const val CHANNEL = "rui_statusbar"
        private const val NOTIFICATION_ID = 7
    }

    private lateinit var wm: WindowManager
    private lateinit var bar: LinearLayout
    private lateinit var time: TextView
    private lateinit var network: TextView
    private lateinit var battery: TextView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rui_statusbar", MODE_PRIVATE) }

    private val tick = object : Runnable {
        override fun run() {
            if (!::bar.isInitialized) return
            updateValues()
            // The clock only needs minute-level refresh; avoid needless work every second.
            handler.postDelayed(this, 15_000L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REFRESH -> updateStyleAndPosition()
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Rui StatusBar")
                .setContentText("狀態列顯示服務運作中")
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            createOverlay()

            val filter = IntentFilter(ACTION_REFRESH).apply { addAction(Intent.ACTION_BATTERY_CHANGED) }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            updateValues()
            handler.postDelayed(tick, 15_000L)
        } catch (e: Exception) {
            android.util.Log.e("RuiStatusBar", "Service startup failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Rui StatusBar", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun createOverlay() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(6, 0, 6, 0)
            clipChildren = false
            clipToPadding = false
        }

        time = textView()
        network = textView()
        battery = textView()
        bar.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        bar.addView(android.view.View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(network, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        bar.addView(battery, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val height = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else (24 * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        wm.addView(bar, params)
        updateStyleAndPosition()
    }

    private fun textView() = TextView(this).apply {
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(3, 0, 3, 0)
        includeFontPadding = false
        setSingleLine(true)
    }

    private fun updateStyleAndPosition() {
        if (!::bar.isInitialized) return
        val mode = prefs.getInt("colorMode", 1)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val color = when (mode) { 2 -> Color.WHITE; 3 -> Color.BLACK; else -> if (dark) Color.WHITE else Color.BLACK }
        val size = prefs.getInt("font", 16) * prefs.getInt("scale", 100) / 100f
        listOf(time, network, battery).forEach { it.setTextColor(color); it.textSize = size }
        params.x = prefs.getInt("x", 0)
        params.y = prefs.getInt("y", 0)
        try { wm.updateViewLayout(bar, params) } catch (e: Exception) {
            android.util.Log.e("RuiStatusBar", "Overlay update failed", e)
        }
        updateValues()
    }

    private fun updateValues() {
        if (!::bar.isInitialized) return
        time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            network.text = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "⌁"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "▂▄▆█"
                else -> "·"
            }
        } catch (e: Exception) {
            network.text = "·"
            android.util.Log.w("RuiStatusBar", "Network status unavailable", e)
        }
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) updateBattery(batteryIntent)
    }

    private fun updateBattery(intent: Intent) {
        if (!::battery.isInitialized) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        battery.text = if (percent >= 0) "▰ $percent%" else "▰ --%"
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (::bar.isInitialized) try { wm.removeView(bar) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
