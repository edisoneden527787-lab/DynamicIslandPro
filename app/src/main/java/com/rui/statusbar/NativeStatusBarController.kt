package com.rui.statusbar

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Hides native status-bar contents through Android's supported StatusBar shell command.
 * This avoids the legacy policy_control setting, which some OEM builds ignore.
 */
object NativeStatusBarController {
    private const val PREFS = "rui_statusbar"
    private const val BACKUP_KEY = "nativeStatusBarPolicyBackup"
    private const val HAD_VALUE_KEY = "nativeStatusBarPolicyHadValue"
    private const val HIDE_FLAGS =
        "cmd statusbar send-disable-flag system-icons clock notification-icons"

    fun hasShizukuPermission(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun setHidden(context: Context, hidden: Boolean): Result<String> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (hidden) {
            // Save the legacy policy for safe restoration, but do not depend on it to hide icons.
            val current = shell("settings get global policy_control").trim()
            if (!prefs.contains(BACKUP_KEY)) {
                prefs.edit()
                    .putBoolean(HAD_VALUE_KEY, current.isNotEmpty() && current != "null")
                    .putString(BACKUP_KEY, if (current == "null") "" else current)
                    .commit()
            }
            shell(HIDE_FLAGS)
            prefs.edit().putBoolean("nativeStatusBarShellHidden", true).commit()
            "已送出隱藏原生狀態列圖示與時鐘指令"
        } else {
            // Clear only the flags set by this controller. The AOSP shell command's
            // stable token makes this reversible across separate shell invocations.
            shell("cmd statusbar send-disable-flag none")
            val hadValue = prefs.getBoolean(HAD_VALUE_KEY, false)
            val previous = prefs.getString(BACKUP_KEY, "").orEmpty()
            if (hadValue) shell("settings put global policy_control ${quote(previous)}")
            else shell("settings delete global policy_control")
            prefs.edit()
                .remove(BACKUP_KEY)
                .remove(HAD_VALUE_KEY)
                .putBoolean("nativeStatusBarShellHidden", false)
                .commit()
            "已恢復原生狀態列"
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
