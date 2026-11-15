package com.rui.statusbar

import android.app.*
import android.content.*
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.*
import android.telephony.TelephonyManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class StatusBarService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.rui.statusbar.REFRESH"
        private const val CHANNEL = "rui_statusbar"
        private const val NOTIFICATION_ID = 7
    }

    private lateinit var wm: WindowManager
    private lateinit var overlay: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var time: TextView
    private lateinit var wifi: WifiIconView
    private lateinit var cellular: CellularIconView
    private lateinit var networkType: TextView
    private lateinit var battery: BatteryIconView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rui_statusbar", MODE_PRIVATE) }
    private var textColor = Color.BLACK
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        handler.post { reapplyNativeStatusBarIfNeeded() }
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        android.util.Log.w("RuiStatusBar", "Shizuku disconnected; native status bar restore is pending")
    }
    @Volatile private var displayOverrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
    private val telephonyDisplayCallback by lazy {
        object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                displayOverrideNetworkType = info.overrideNetworkType
                handler.post { if (::overlay.isInitialized) updateValues() }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!::overlay.isInitialized) return
            updateValues()
            handler.postDelayed(this, 5_000L)
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
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
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
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                    tm.registerTelephonyCallback(mainExecutor, telephonyDisplayCallback)
                } catch (e: Exception) {
                    android.util.Log.w("RuiStatusBar", "Unable to register 5G display callback", e)
                }
            }
            updateValues()
            reapplyNativeStatusBarIfNeeded()
            handler.postDelayed(tick, 5_000L)
        } catch (e: Exception) {
            android.util.Log.e("RuiStatusBar", "Service startup failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && ::overlay.isInitialized) {
            updateStyleAndPosition()
        }
        return START_STICKY
    }

    private fun reapplyNativeStatusBarIfNeeded() {
        if (!prefs.getBoolean("hideNativeStatusBar", false)) return
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            NativeStatusBarController.setHidden(this, true).getOrThrow()
            android.util.Log.i("RuiStatusBar", "Native status bar hide reapplied")
        } catch (e: Exception) {
            android.util.Log.e("RuiStatusBar", "Unable to reapply native status bar hide", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Rui StatusBar", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun createOverlay() {
        val statusHeightId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusHeight = if (statusHeightId > 0) resources.getDimensionPixelSize(statusHeightId) else dp(26f).toInt()

        overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7f).toInt(), 0, dp(7f).toInt(), 0)
            clipChildren = false
            clipToPadding = false
        }

        time = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(true)
        }
        wifi = WifiIconView(this)
        cellular = CellularIconView(this)
        networkType = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(true)
            setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
        }
        battery = BatteryIconView(this)
        val initialScale = prefs.getInt("scale", 100).coerceIn(70, 150) / 100f
        battery.layoutParams = iconParams((prefs.getInt("batteryLength", 42) * initialScale).toInt(), (22 * initialScale).toInt()).apply { leftMargin = dp(3f).toInt() }

        content.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        content.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        content.addView(cellular, iconParams(19, 19))
        content.addView(networkType, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        content.addView(wifi, iconParams(20, 19))
        content.addView(battery, iconParams(prefs.getInt("batteryLength", 42).coerceIn(30, 58), 22))
        // The row itself must be taller than the native status bar when the user enlarges or moves it.
        // A full-screen parent alone does not prevent child TextViews from clipping at statusHeight.
        val requestedTextSize = prefs.getInt("font", 16) * prefs.getInt("scale", 100) / 100f
        val contentHeight = maxOf(statusHeight, dp(maxOf(40f, requestedTextSize * 1.9f + 12f)).toInt(), dp(46f).toInt())
        content.minimumHeight = contentHeight
        overlay.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, contentHeight, Gravity.TOP))
        overlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Do not let Android inset the overlay below the status-bar safe area.
            // The overlay must originate at physical display coordinate (0, 0).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        wm.addView(overlay, params)
        updateStyleAndPosition()
    }

    private fun iconParams(width: Int, height: Int) =
        LinearLayout.LayoutParams(dp(width.toFloat()).toInt(), dp(height.toFloat()).toInt()).apply {
            leftMargin = dp(3f).toInt()
        }

    private fun updateStyleAndPosition() {
        if (!::overlay.isInitialized) return
        val mode = prefs.getInt("colorMode", 1)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        textColor = when (mode) { 2 -> Color.WHITE; 3 -> Color.BLACK; else -> if (dark) Color.WHITE else Color.BLACK }
        val size = prefs.getInt("font", 16) * prefs.getInt("scale", 100) / 100f
        time.setTextColor(textColor)
        time.textSize = size
        val statusHeightId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusHeight = if (statusHeightId > 0) resources.getDimensionPixelSize(statusHeightId) else dp(26f).toInt()
        val contentHeight = maxOf(statusHeight, dp(maxOf(40f, size * 1.9f + 12f)).toInt(), dp(46f).toInt())
        val contentLp = content.layoutParams as? FrameLayout.LayoutParams
        if (contentLp != null) {
            val requestedWidth = (resources.displayMetrics.widthPixels *
                prefs.getInt("statusWidth", 100).coerceIn(45, 100) / 100f).toInt()
            var changed = false
            if (contentLp.height != contentHeight) { contentLp.height = contentHeight; changed = true }
            if (contentLp.width != requestedWidth) { contentLp.width = requestedWidth; changed = true }
            if (contentLp.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)) {
                contentLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                changed = true
            }
            if (changed) content.layoutParams = contentLp
        }
        val weight = when (prefs.getInt("clockWeight", 2)) {
            1 -> Typeface.create("sans-serif", Typeface.NORMAL)
            3 -> Typeface.create("sans-serif", Typeface.BOLD)
            else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        time.typeface = weight
        battery.setColor(textColor)
        battery.setTextSize(size * 0.72f)
        wifi.setColor(textColor)
        cellular.setColor(textColor)
        networkType.setTextColor(textColor)
        networkType.textSize = (size * 0.62f).coerceIn(8f, 13f)
        cellular.setScale(prefs.getInt("scale", 100) / 100f)
        wifi.setScale(prefs.getInt("scale", 100) / 100f)

        // The overlay window stays full-width; move the inner content itself so X really changes.
        val batteryLp = battery.layoutParams as LinearLayout.LayoutParams
        val iconScale = prefs.getInt("scale", 100).coerceIn(70, 150) / 100f
        val batteryWidth = prefs.getInt("batteryLength", 42).coerceIn(30, 58)
        val scaledBatteryWidth = dp((batteryWidth * iconScale).toFloat()).toInt()
        val scaledBatteryHeight = dp((22f * iconScale)).toInt()
        if (batteryLp.width != scaledBatteryWidth || batteryLp.height != scaledBatteryHeight) {
            batteryLp.width = scaledBatteryWidth
            batteryLp.height = scaledBatteryHeight
            battery.layoutParams = batteryLp
        }
        content.translationX = dp(prefs.getInt("x", 0).toFloat())
        content.translationY = dp(prefs.getInt("y", 0).toFloat())
        cellular.visibility = if (prefs.getBoolean("showCellular", true)) View.VISIBLE else View.GONE
        networkType.visibility = if (prefs.getBoolean("showCellular", true)) View.VISIBLE else View.GONE
        wifi.visibility = if (prefs.getBoolean("showWifi", true)) View.VISIBLE else View.GONE
        updateValues()
    }

    private fun updateValues() {
        if (!::overlay.isInitialized) return
        val custom = prefs.getBoolean("customValues", false)
        time.text = if (custom) prefs.getString("fakeTime", "09:41")
            else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                caps?.transportInfo as? android.net.wifi.WifiInfo else null
            var rssi = if (wifiConnected) wifiInfo?.rssi ?: -127 else -127
            if (wifiConnected && rssi <= -127) {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val fallbackRssi = wifiManager.connectionInfo?.rssi ?: -127
                if (fallbackRssi > -127) rssi = fallbackRssi
            }
            // A real connected Wi-Fi network should not look like zero bars merely because
            // Android redacted the legacy WifiManager RSSI value.
            wifi.level = if (wifiConnected && rssi > -127)
                WifiManager.calculateSignalLevel(rssi, 5).coerceIn(1, 4)
                else if (wifiConnected) 1 else 0
        } catch (e: Exception) {
            wifi.level = 0
            android.util.Log.w("RuiStatusBar", "Wi-Fi status unavailable", e)
        }
        var level = -1
        var networkLabel = "無訊號"
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            level = tm.signalStrength?.level ?: -1
            val inService = tm.serviceState?.state == android.telephony.ServiceState.STATE_IN_SERVICE
            val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.dataNetworkType else tm.networkType
            val isNrDisplay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (displayOverrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                 displayOverrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
            networkLabel = if (!inService) "無訊號" else when {
                network == TelephonyManager.NETWORK_TYPE_NR || isNrDisplay -> "5G"
                network == TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                network == TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi"
                network == TelephonyManager.NETWORK_TYPE_UNKNOWN -> if (level <= 0) "無訊號" else "行動網路"
                else -> if (level <= 0) "無訊號" else "4G"
            }
        } catch (e: SecurityException) {
            android.util.Log.w("RuiStatusBar", "Signal permission not granted", e)
            networkLabel = "無訊號"
        } catch (e: Exception) {
            android.util.Log.w("RuiStatusBar", "Signal status unavailable", e)
            networkLabel = "無訊號"
        }
        if (custom) {
            networkLabel = when (prefs.getString("fakeNetworkType", "自動")) {
                "4G" -> "4G"
                "5G" -> "5G"
                "無訊號" -> "無訊號"
                else -> networkLabel
            }
        }
        networkType.text = networkLabel
        cellular.level = if (custom) prefs.getInt("fakeSignal", 4).coerceIn(0, 4)
            else if (level in 0..4) level else 0
        if (custom) {
            battery.percent = prefs.getInt("fakeBattery", 88).coerceIn(0, 100)
        } else {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) updateBattery(batteryIntent)
        }
    }

    private fun updateBattery(intent: Intent) {
        if (!::battery.isInitialized) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        battery.percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        handler.removeCallbacks(tick)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                tm.unregisterTelephonyCallback(telephonyDisplayCallback)
            } catch (_: Exception) {}
        }
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (::overlay.isInitialized) try { wm.removeView(overlay) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private class WifiIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(2f) }
        private var color = Color.BLACK
        var level = 0
            set(value) { field = value.coerceIn(0, 4); invalidate() }
        private fun dp(v: Float) = v * resources.displayMetrics.density
        fun setColor(value: Int) { color = value; invalidate() }
        fun setScale(value: Float) { scaleX = value; scaleY = value }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = color
            val cx = width / 2f
            val cy = height * 0.78f
            val radii = floatArrayOf(dp(13f), dp(9f), dp(5f))
            radii.forEachIndexed { index, r ->
                paint.alpha = if (level >= (3 - index)) 255 else 65
                canvas.drawArc(RectF(cx-r, cy-r, cx+r, cy+r), 225f, 90f, false, paint)
            }
            paint.style = Paint.Style.FILL
            paint.alpha = if (level > 0) 255 else 65
            canvas.drawCircle(cx, cy, dp(1.7f), paint)
            paint.style = Paint.Style.STROKE
        }
    }

    private class CellularIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var color = Color.BLACK
        var level = 3
            set(value) { field = value; invalidate() }
        private fun dp(v: Float) = v * resources.displayMetrics.density
        fun setColor(value: Int) { color = value; invalidate() }
        fun setScale(value: Float) { scaleX = value; scaleY = value }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = color
            val base = height - dp(2f)
            val barW = dp(3f)
            val gap = dp(1.5f)
            for (i in 0..3) {
                val h = dp(5f + i * 3.2f)
                paint.alpha = if (i < level.coerceIn(0, 4)) 255 else 75
                val left = dp(1f) + i * (barW + gap)
                canvas.drawRoundRect(RectF(left, base-h, left+barW, base), dp(1f), dp(1f), paint)
            }
        }
    }

    private class BatteryIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var color = Color.BLACK
        private var textSizePx = 10f
        var percent = -1
            set(value) { field = value; invalidate() }
        fun setColor(value: Int) { color = value; invalidate() }
        fun setTextSize(value: Float) { textSizePx = value * resources.displayMetrics.scaledDensity; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val body = RectF(1f*d, 4f*d, width-4f*d, height-4f*d)
            val radius = 4f*d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.35f*d
            paint.color = color
            paint.alpha = 235
            canvas.drawRoundRect(body, radius, radius, paint)
            val tip = RectF(width-3.5f*d, height*0.37f, width-0.5f*d, height*0.63f)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(tip, 1f*d, 1f*d, paint)
            val inner = RectF(body.left+2f*d, body.top+2f*d, body.right-2f*d, body.bottom-2f*d)
            val fillFraction = if (percent >= 0) percent / 100f else 0.5f
            val fillRight = inner.left + (inner.width() * fillFraction).coerceIn(0f, inner.width())
            // Make the unfilled half white and the filled half follow the status-bar color.
            // The percentage is then drawn in the inverse color on each half.
            val inverseColor = if (color == Color.WHITE) Color.BLACK else Color.WHITE
            paint.style = Paint.Style.FILL
            paint.color = inverseColor
            paint.alpha = 255
            canvas.drawRoundRect(inner, 1.5f*d, 1.5f*d, paint)
            paint.color = color
            canvas.drawRoundRect(RectF(inner.left, inner.top, min(fillRight, inner.right), inner.bottom), 1.5f*d, 1.5f*d, paint)
            val label = if (percent >= 0) percent.toString() else "--"
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = textSizePx.coerceAtLeast(8f*d)
            paint.textAlign = Paint.Align.CENTER
            val fm = paint.fontMetrics
            val baseline = body.centerY() - (fm.ascent + fm.descent) / 2f
            // Split the digits exactly at the fill boundary: white on dark fill, black on white remainder.
            val save = canvas.save()
            canvas.clipRect(inner.left, inner.top, min(fillRight, inner.right), inner.bottom)
            paint.color = inverseColor
            canvas.drawText(label, body.centerX(), baseline, paint)
            canvas.restoreToCount(save)
            val saveRemainder = canvas.save()
            canvas.clipRect(min(fillRight, inner.right), inner.top, inner.right, inner.bottom)
            paint.color = color
            canvas.drawText(label, body.centerX(), baseline, paint)
            canvas.restoreToCount(saveRemainder)
        }
    }
}
