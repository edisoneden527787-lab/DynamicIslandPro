package com.rui.statusbar

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Requests immersive status-bar hiding through Android's legacy global policy.
 * Do not use StatusBar disable flags here: they only hide selected icons and
 * leave the native status-bar area visible on many Android builds.
 */
object NativeStatusBarController {
    private const val PREFS = "rui_statusbar"
    private const val BACKUP_KEY = "nativeStatusBarPolicyBackup"
    private const val HAD_VALUE_KEY = "nativeStatusBarPolicyHadValue"
    private const val IMMERSIVE_POLICY = "immersive.status=*"

    fun hasShizukuPermission(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun setHidden(context: Context, hidden: Boolean): Result<String> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (hidden) {
            val current = shell("settings get global policy_control").trim()
            if (!prefs.contains(BACKUP_KEY)) {
                prefs.edit()
                    .putBoolean(HAD_VALUE_KEY, current.isNotEmpty() && current != "null")
                    .putString(BACKUP_KEY, if (current == "null") "" else current)
                    .commit()
            }

            // Quote the asterisk so the shell cannot expand it as a filename glob.
            shell("settings put global policy_control '${IMMERSIVE_POLICY}'")
            val applied = shell("settings get global policy_control").trim()
            check(applied == IMMERSIVE_POLICY) {
                "OriginOS 沒有接受整條狀態列隱藏設定（讀回：${applied.ifBlank { "空值" }}）。已停止使用只會隱藏時鐘／圖示的替代指令。"
            }
            prefs.edit().putBoolean("nativeStatusBarShellHidden", true).commit()
            "已套用沉浸式狀態列隱藏設定"
        } else {
            val hadValue = prefs.getBoolean(HAD_VALUE_KEY, false)
            val previous = prefs.getString(BACKUP_KEY, "").orEmpty()
            if (hadValue) {
                shell("settings put global policy_control ${quote(previous)}")
            } else {
                shell("settings delete global policy_control")
            }
            prefs.edit()
                .remove(BACKUP_KEY)
                .remove(HAD_VALUE_KEY)
                .putBoolean("nativeStatusBarShellHidden", false)
                .commit()
            "已還原原生狀態列設定"
        }
    }

    private fun shell(command: String): String {
        check(Shizuku.pingBinder()) { "Shizuku 服務尚未啟動，請先在 Shizuku App 啟動服務" }
        check(Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Rui StatusBar 尚未取得 Shizuku 授權"
        }
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { error.ifBlank { output.ifBlank { "Shizuku 指令執行失敗（$exit）" } } }
        return output
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
