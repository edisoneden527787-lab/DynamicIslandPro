package com.rui.statusbar

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Hides native status-bar contents through the same StatusBar shell command
 * used by the previously working build. This is intentionally restored because
 * OriginOS does not reliably apply the legacy policy_control immersive setting.
 */
object NativeStatusBarController {
    private const val PREFS = "rui_statusbar"
    private const val HIDE_FLAGS =
        "cmd statusbar send-disable-flag system-icons clock notification-icons"

    fun hasShizukuPermission(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun setHidden(context: Context, hidden: Boolean): Result<String> = runCatching {
        if (hidden) {
            shell(HIDE_FLAGS)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("nativeStatusBarShellHidden", true)
                .commit()
            "已送出隱藏原生狀態列指令"
        } else {
            shell("cmd statusbar send-disable-flag none")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
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
}
