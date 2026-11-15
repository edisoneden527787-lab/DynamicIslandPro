package com.rui.statusbar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.Color
import android.view.ViewGroup

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("rui_statusbar", MODE_PRIVATE) }
    private lateinit var status: TextView
    private val signalPermissionRequest = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = resources.displayMetrics.density
            setPadding((20*d).toInt(), (24*d).toInt(), (20*d).toInt(), (32*d).toInt())
            setBackgroundColor(0xFFF3F5F9.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Rui StatusBar"
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFF172033.toInt())
            setPadding(0, 0, 0, 5)
        })
        status = TextView(this).apply {
            text = "自訂你的透明狀態列"
            textSize = 14f
            setTextColor(0xFF718096.toInt())
            setPadding(0, 0, 0, 16)
        }
        root.addView(status)
        root.addView(label("顯示與權限"))
        root.addView(button("授予「顯示在其他 App 上層」權限") {
            if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else startOverlay()
        })
        root.addView(button("啟用狀態列") { startOverlay() })
        root.addView(button("停用狀態列") {
            stopService(Intent(this, StatusBarService::class.java))
            status.text = "狀態列已停用"
        })

        root.addView(label("圖示顯示開關"))
        addToggle(root, "顯示行動訊號", "showCellular", true) { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), signalPermissionRequest)
            }
        }
        addToggle(root, "顯示 Wi-Fi", "showWifi", true)

        root.addView(label("字體大小"))
        addSlider(root, 10, 28, prefs.getInt("font", 16), " sp") { prefs.edit().putInt("font", it).apply(); refresh() }
        root.addView(label("時鐘字體粗細（1 一般／2 中等／3 粗體）"))
        addSlider(root, 1, 3, prefs.getInt("clockWeight", 2), "") { prefs.edit().putInt("clockWeight", it).apply(); refresh() }
        root.addView(label("電池圖示長度"))
        addSlider(root, 30, 58, prefs.getInt("batteryLength", 42), " dp") { prefs.edit().putInt("batteryLength", it).apply(); refresh() }
        root.addView(label("整體大小"))
        addSlider(root, 70, 150, prefs.getInt("scale", 100), "%") { prefs.edit().putInt("scale", it).apply(); refresh() }
        root.addView(label("狀態列寬度"))
        addSlider(root, 45, 100, prefs.getInt("statusWidth", 100), "%") { prefs.edit().putInt("statusWidth", it).apply(); refresh() }

        root.addView(label("玩玩看：自訂狀態數值"))
        addToggle(root, "啟用自訂時間／電量／訊號", "customValues", false)
        val fakeTime = EditText(this).apply {
            hint = "輸入時間，例如 09:41"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
            setText(prefs.getString("fakeTime", "09:41"))
        }
        root.addView(fakeTime)
        root.addView(button("套用自訂時間") {
            val value = fakeTime.text.toString().trim()
            if (value.matches(Regex("\\d{1,2}:\\d{2}"))) {
                prefs.edit().putString("fakeTime", value).putBoolean("customValues", true).apply()
                refresh()
                status.text = "已套用自訂時間"
            } else {
                fakeTime.error = "請輸入例如 09:41 的格式"
            }
        })
        root.addView(label("自訂電量"))
        addSlider(root, 0, 100, prefs.getInt("fakeBattery", 88), "%") { prefs.edit().putInt("fakeBattery", it).putBoolean("customValues", true).apply(); refresh() }
        root.addView(label("自訂訊號格數"))
        addSlider(root, 0, 4, prefs.getInt("fakeSignal", 4), " 格") { prefs.edit().putInt("fakeSignal", it).putBoolean("customValues", true).apply(); refresh() }
        root.addView(label("自訂網路標示"))
        val networkOptions = listOf("自動", "4G", "5G", "無訊號")
        val networkSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, networkOptions)
            setSelection(networkOptions.indexOf(prefs.getString("fakeNetworkType", "自動")).coerceAtLeast(0))
        }
        var networkSpinnerReady = false
        networkSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!networkSpinnerReady) { networkSpinnerReady = true; return }
                prefs.edit().putString("fakeNetworkType", networkOptions[position]).putBoolean("customValues", true).apply()
                refresh()
            }
        }
        root.addView(networkSpinner)
        root.addView(button("一鍵恢復系統時間／電量／訊號") {
            prefs.edit().putBoolean("customValues", false).apply()
            refresh()
            status.text = "已恢復手機系統數值"
        })
        root.addView(label("X 軸位置（左右）"))
        addSlider(root, -150, 150, prefs.getInt("x", 0), " dp") { prefs.edit().putInt("x", it).apply(); refresh() }
        root.addView(label("Y 軸位置（上下）"))
        addSlider(root, -80, 150, prefs.getInt("y", 0), " dp") { prefs.edit().putInt("y", it).apply(); refresh() }

        root.addView(label("文字／圖示顏色"))
        val colors = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        colors.addView(RadioButton(this).apply { text = "跟隨系統"; id = 1 })
        colors.addView(RadioButton(this).apply { text = "白色"; id = 2 })
        colors.addView(RadioButton(this).apply { text = "黑色"; id = 3 })
        colors.check(prefs.getInt("colorMode", 1))
        colors.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("colorMode", id).apply(); refresh() }
        root.addView(colors)
        root.addView(TextView(this).apply {
            text = "XY 位置會即時套用到狀態列內容。背景透明，不接收觸控。"
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

    private fun addToggle(root: LinearLayout, title: String, key: String, default: Boolean, onChanged: (Boolean) -> Unit = {}) {
        val toggle = Switch(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChanged(checked)
                refresh()
            }
            setPadding(0, 8, 0, 8)
        }
        root.addView(toggle)
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(0xFF52627A.toInt())
        setPadding(0, 22, 0, 8)
    }

    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(0xFF3867D6.toInt())
        setPadding(18, 8, 18, 8)
        setOnClickListener { action() }
    }

    private fun addSlider(root: LinearLayout, min: Int, max: Int, value: Int, suffix: String, changed: (Int) -> Unit) {
        val valueText = TextView(this).apply { textSize = 13f; setTextColor(0xFF555555.toInt()) }
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, this.max)
            progressTintList = ColorStateList.valueOf(0xFF3867D6.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF3867D6.toInt())
        }
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
            status.text = "狀態列啟動指令已送出"
        } catch (e: Exception) {
            android.util.Log.e("RuiStatusBar", "Unable to start status bar service", e)
            status.text = "啟動失敗：" + (e.message ?: e.javaClass.simpleName)
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        // Deliver updates directly to the running service; sliders apply while being dragged.
        val intent = Intent(this, StatusBarService::class.java).setAction(StatusBarService.ACTION_REFRESH)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            android.util.Log.w("RuiStatusBar", "Live refresh delivery failed", e)
            sendBroadcast(Intent(StatusBarService.ACTION_REFRESH).setPackage(packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == signalPermissionRequest) {
            status.text = if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                "已取得訊號強度權限" else "未授權讀取訊號強度，仍會顯示訊號圖示"
            refresh()
        }
    }
}
