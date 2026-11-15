package com.rui.statusbar

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Applies the immersive status-bar policy through Shizuku's shell identity.
 * The previous global policy_control value is preserved and restored on disable.
 */
object NativeStatusBarController {
    private const val PREFS = "rui_statusbar"
    private const val BACKUP_KEY = "nativeStatusBarPolicyBackup"
    private const val HAD_VALUE_KEY = "nativeStatusBarPolicyHadValue"

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
                    .apply()
            }
            val policies = current.split(":")
                .filter { it.isNotBlank() && !it.startsWith("immersive.status=") }
                .toMutableList()
            policies.add("immersive.status=*")
            val updated = policies.joinToString(":")
            shell("settings put global policy_control ${quote(updated)}")
            val check = shell("settings get global policy_control").trim()
            check(check == updated) { "系統沒有接受隱藏狀態列設定" }
            "已送出隱藏系統狀態列指令"
        } else {
            check(prefs.contains(BACKUP_KEY)) { "找不到先前備份的系統設定" }
            val hadValue = prefs.getBoolean(HAD_VALUE_KEY, false)
            val previous = prefs.getString(BACKUP_KEY, "").orEmpty()
            if (hadValue) shell("settings put global policy_control ${quote(previous)}")
            else shell("settings delete global policy_control")
            val restored = shell("settings get global policy_control").trim()
            val expected = if (hadValue) previous else "null"
            check(restored == expected) { "系統沒有成功恢復原生狀態列設定，備份已保留，請再試一次" }
            prefs.edit().remove(BACKUP_KEY).remove(HAD_VALUE_KEY).commit()
            "已恢復原生狀態列設定"
        }
    }

    private fun shell(command: String): String {
        check(Shizuku.pingBinder()) { "Shizuku 服務尚未啟動，請先在 Shizuku App 啟動服務" }
        check(Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Rui StatusBar 尚未取得 Shizuku 授權"
        }
        // Shizuku 13.x exposes the process bridge as a private static method.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { error.ifBlank { "Shizuku 指令執行失敗（$exit）" } }
        return output
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
