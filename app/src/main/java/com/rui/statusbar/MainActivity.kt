package com.rui.statusbar

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("rui_statusbar", MODE_PRIVATE) }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Rui StatusBar"
            textSize = 26f
            setTextColor(0xFF111111.toInt())
        })
        status = TextView(this).apply {
            text = "頂端顯示時間、網路與電量"
            textSize = 14f
            setTextColor(0xFF555555.toInt())
        }
        root.addView(status)
        root.addView(label("顯示與權限"))
        root.addView(button("授予「顯示在其他 App 上層」權限") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startOverlay()
            }
        })
        root.addView(button("啟用狀態列") { startOverlay() })
        root.addView(button("停用狀態列") {
            stopService(Intent(this, StatusBarService::class.java))
            status.text = "狀態列已停用"
        })
        root.addView(label("字體大小"))
        addSlider(root, 10, 28, prefs.getInt("font", 16), " sp") { prefs.edit().putInt("font", it).apply(); refresh() }
        root.addView(label("整體大小"))
        addSlider(root, 70, 150, prefs.getInt("scale", 100), "%") { prefs.edit().putInt("scale", it).apply(); refresh() }
        root.addView(label("X 軸位置"))
        addSlider(root, -100, 100, prefs.getInt("x", 0), " px") { prefs.edit().putInt("x", it).apply(); refresh() }
        root.addView(label("Y 軸位置"))
        addSlider(root, -60, 120, prefs.getInt("y", 0), " px") { prefs.edit().putInt("y", it).apply(); refresh() }
        root.addView(label("文字／圖示顏色"))
        val colors = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        colors.addView(RadioButton(this).apply { text = "跟隨系統"; id = 1 })
        colors.addView(RadioButton(this).apply { text = "白色"; id = 2 })
        colors.addView(RadioButton(this).apply { text = "黑色"; id = 3 })
        colors.check(prefs.getInt("colorMode", 1))
        colors.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("colorMode", id).apply(); refresh() }
        root.addView(colors)
        root.addView(TextView(this).apply {
            text = "提示：跟隨系統模式依系統深色模式切換黑白。狀態列背景透明，且不接收觸控。"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 16, 0, 16)
        })
        root.addView(button("恢復預設值") {
            prefs.edit().clear().apply()
            buildUi()
        })
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 16f
        setTextColor(0xFF222222.toInt())
        setPadding(0, 20, 0, 6)
    }

    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s
        setOnClickListener { action() }
    }

    private fun addSlider(root: LinearLayout, min: Int, max: Int, value: Int, suffix: String, changed: (Int) -> Unit) {
        val valueText = TextView(this).apply { textSize = 13f; setTextColor(0xFF555555.toInt()) }
        val seek = SeekBar(this).apply { this.max = max - min; progress = (value - min).coerceIn(0, this.max) }
        fun show(v: Int) { valueText.text = v.toString() + suffix }
        show(value)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                show(v)
                if (fromUser) changed(v)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        root.addView(valueText)
        root.addView(seek)
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "請先授予 Overlay 權限"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        try {
            val intent = Intent(this, StatusBarService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            status.text = "啟動指令已送出；若未顯示請重新開啟 App 查看"
        } catch (e: Exception) {
            android.util.Log.e("RuiStatusBar", "Unable to start status bar service", e)
            status.text = "啟動失敗：" + (e.message ?: e.javaClass.simpleName)
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        sendBroadcast(Intent(StatusBarService.ACTION_REFRESH).setPackage(packageName))
    }
}
