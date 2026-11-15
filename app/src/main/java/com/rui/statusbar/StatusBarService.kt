package com.rui.statusbar

import android.app.*
import android.content.*
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var battery: BatteryIconView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rui_statusbar", MODE_PRIVATE) }
    private var textColor = Color.BLACK

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
            updateValues()
            handler.postDelayed(tick, 5_000L)
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
        battery = BatteryIconView(this)

        content.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        content.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        content.addView(cellular, iconParams(19, 19))
        content.addView(wifi, iconParams(20, 19))
        content.addView(battery, iconParams(58, 26).apply { leftMargin = dp(3f).toInt() })
        overlay.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, statusHeight, Gravity.TOP))
        overlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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
        cellular.setScale(prefs.getInt("scale", 100) / 100f)
        wifi.setScale(prefs.getInt("scale", 100) / 100f)

        // The overlay window stays full-width; move the inner content itself so X really changes.
        content.translationX = dp(prefs.getInt("x", 0).toFloat())
        content.translationY = dp(prefs.getInt("y", 0).toFloat())
        cellular.visibility = if (prefs.getBoolean("showCellular", true)) View.VISIBLE else View.GONE
        wifi.visibility = if (prefs.getBoolean("showWifi", true)) View.VISIBLE else View.GONE
        updateValues()
    }

    private fun updateValues() {
        if (!::overlay.isInitialized) return
        time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            wifi.isConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) {
            wifi.isConnected = false
            android.util.Log.w("RuiStatusBar", "Wi-Fi status unavailable", e)
        }
        var level = -1
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            level = tm.signalStrength?.level ?: -1
        } catch (e: SecurityException) {
            android.util.Log.w("RuiStatusBar", "Signal permission not granted", e)
        } catch (e: Exception) {
            android.util.Log.w("RuiStatusBar", "Signal status unavailable", e)
        }
        cellular.level = if (level in 0..4) level else 3
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) updateBattery(batteryIntent)
    }

    private fun updateBattery(intent: Intent) {
        if (!::battery.isInitialized) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        battery.percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (::overlay.isInitialized) try { wm.removeView(overlay) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private class WifiIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(2f) }
        private var color = Color.BLACK
        var isConnected = false
            set(value) { field = value; invalidate() }
        private fun dp(v: Float) = v * resources.displayMetrics.density
        fun setColor(value: Int) { color = value; invalidate() }
        fun setScale(value: Float) { scaleX = value; scaleY = value }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = color
            paint.alpha = if (isConnected) 255 else 115
            val cx = width / 2f
            val cy = height * 0.78f
            val radii = floatArrayOf(dp(13f), dp(9f), dp(5f))
            radii.forEach { r ->
                canvas.drawArc(RectF(cx-r, cy-r, cx+r, cy+r), 225f, 90f, false, paint)
            }
            paint.style = Paint.Style.FILL
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
            val body = RectF(1f*d, 4f*d, width-5f*d, height-4f*d)
            val radius = 3f*d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f*d
            paint.color = color
            paint.alpha = 235
            canvas.drawRoundRect(body, radius, radius, paint)
            val tip = RectF(width-4.5f*d, height*0.37f, width-1f*d, height*0.63f)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(tip, 1f*d, 1f*d, paint)
            val inner = RectF(body.left+2f*d, body.top+2f*d, body.right-2f*d, body.bottom-2f*d)
            val fillRight = inner.left + (inner.width() * (if (percent >= 0) percent / 100f else 0.5f)).coerceAtLeast(2f*d)
            paint.color = color
            canvas.drawRoundRect(RectF(inner.left, inner.top, min(fillRight, inner.right), inner.bottom), 1.5f*d, 1.5f*d, paint)
            val label = if (percent >= 0) percent.toString() else "--"
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = textSizePx.coerceAtLeast(8f*d)
            paint.textAlign = Paint.Align.CENTER
            val fm = paint.fontMetrics
            val baseline = body.centerY() - (fm.ascent + fm.descent) / 2f
            // Draw a contrasting outline around the digits so they remain readable over the fill.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * d
            paint.color = if (color == Color.WHITE) Color.BLACK else Color.WHITE
            paint.alpha = 255
            canvas.drawText(label, body.centerX(), baseline, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (percent >= 55) Color.WHITE else color
            paint.alpha = 255
            canvas.drawText(label, body.centerX(), baseline, paint)
        }
    }
}
