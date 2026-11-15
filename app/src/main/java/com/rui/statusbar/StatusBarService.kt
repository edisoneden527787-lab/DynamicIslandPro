package com.rui.statusbar

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatusBarService : Service() {
    companion object { const val ACTION_REFRESH="com.rui.statusbar.REFRESH"; private const val CHANNEL="rui_statusbar" }
    private lateinit var wm:WindowManager
    private lateinit var bar:LinearLayout
    private lateinit var time:TextView
    private lateinit var network:TextView
    private lateinit var battery:TextView
    private lateinit var params:WindowManager.LayoutParams
    private val handler=Handler(Looper.getMainLooper())
    private var telephonyCallback:TelephonyCallback?=null
    @Volatile private var signalIndicator = "··"
    private val prefs by lazy { getSharedPreferences("rui_statusbar",MODE_PRIVATE) }
    private val tick=object:Runnable { override fun run(){ updateValues(); handler.postDelayed(this,1000) } }
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?){ updateStyleAndPosition() }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Rui StatusBar 正在運作").setContentText("透明狀態列覆蓋層已啟用").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(7, notification)
        }
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            createOverlay()
        } catch (e: SecurityException) {
            android.util.Log.e("RuiStatusBar", "Overlay permission denied", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.e("RuiStatusBar", "Unable to attach status bar overlay", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(ACTION_REFRESH), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, IntentFilter(ACTION_REFRESH))
        }
        registerSignalListener()
        handler.post(tick)
    }
    private fun createChannel() {
        if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL,"Rui StatusBar",NotificationManager.IMPORTANCE_LOW))
    }
    private fun createOverlay() {
        bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.TRANSPARENT); setPadding(6,0,6,0) }
        time=textView(); network=textView(); battery=textView()
        bar.addView(time); bar.addView(network); bar.addView(battery)
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) }
            ?: (24 * resources.displayMetrics.density).toInt()
        params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,statusBarHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.START
                x=0
                y=0
            }
        wm.addView(bar,params)
        updateStyleAndPosition()
    }
    private fun textView()=TextView(this).apply { textSize=16f; setPadding(3,0,3,0); includeFontPadding=false }
    private fun updateStyleAndPosition() {
        if(!::bar.isInitialized) return
        val mode=prefs.getInt("colorMode",1)
        val darkSystem=(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES
        val color=when(mode){2->Color.WHITE;3->Color.BLACK;else->if(darkSystem) Color.WHITE else Color.BLACK}
        val size=prefs.getInt("font",16)*prefs.getInt("scale",100)/100f
        listOf(time,network,battery).forEach { it.setTextColor(color); it.textSize=size }
        params.x=prefs.getInt("x",0); params.y=prefs.getInt("y",0)
        try { wm.updateViewLayout(bar,params) } catch(_:Exception) {}
        updateValues()
    }
    private fun updateValues() {
        if(!::bar.isInitialized) return
        time.text=SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date())
        val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi=caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        val cellular=caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true
        network.text=if(wifi) "  Wi-Fi" else if(cellular) "  "+signalIndicator else "  ·"
        val sticky=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
        val scale=sticky?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
        val pct=if(level>=0 && scale>0) level*100/scale else -1
        battery.text=if(pct>=0) "  "+pct+"%" else "  --%"
    }
    private fun registerSignalListener() {
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED) {
            try {
                val tm=getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val callback=object:TelephonyCallback(),TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength:SignalStrength) {
                        signalIndicator = "▂▄▆█".take((signalStrength.level+1).coerceIn(1,4))
                        handler.post { updateValues() }
                    }
                    private fun isFinishing()=false
                }
                telephonyCallback=callback
                tm.registerTelephonyCallback(mainExecutor,callback)
            } catch(_:Exception) {}
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(receiver) } catch(_:Exception) {}
        if(Build.VERSION.SDK_INT>=31 && telephonyCallback!=null) try {
            (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).unregisterTelephonyCallback(telephonyCallback!!)
        } catch(_:Exception) {}
        if(::bar.isInitialized) try { wm.removeView(bar) } catch(_:Exception) {}
        super.onDestroy()
    }
    override fun onBind(intent:Intent?)=null
}
