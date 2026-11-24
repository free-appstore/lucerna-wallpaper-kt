package com.lucerna.app.platform

import java.util.concurrent.TimeUnit

/**
 * 确保 macOS「墙纸 → 在所有空间中显示」处于开启状态。
 *
 * Apple 没有公开这个开关的 API，只能通过 System Settings 辅助功能操作；
 * 成功一次后本进程内不再重复打开设置。失败时返回 false，由调用方回退到
 * 壁纸库同步或 NSWorkspace。
 */
internal object MacSharedWallpaper {

    private var attempted = false

    @Volatile
    private var enabled = false

    fun ensure(): Boolean {
        if (!attempted) {
            attempted = true
            enabled = runCatching { toggleViaSystemSettings() }.getOrElse { false }
            println("[Lucerna] show-on-all-spaces=$enabled")
        }
        return enabled
    }

    private fun toggleViaSystemSettings(): Boolean {
        val script = """
            set wasRunning to (do shell script "pgrep -x 'System Settings' >/dev/null && echo yes || echo no") is "yes"
            do shell script "open 'x-apple.systempreferences:com.apple.Wallpaper-Settings.extension'"
            delay 2
            set found to false
            tell application "System Events"
                tell process "System Settings"
                    repeat with c in (entire contents of window 1)
                        try
                            if class of c is checkbox then
                                set n to name of c as text
                                if n contains "Space" or n contains "空间" then
                                    if value of c is 0 then click c
                                    set found to true
                                    exit repeat
                                end if
                            end if
                        end try
                    end repeat
                end tell
            end tell
            if not wasRunning then tell application "System Settings" to quit
            return found
        """.trimIndent()

        val process = ProcessBuilder("osascript", "-e", script)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        if (process.exitValue() != 0) {
            println("[Lucerna] show-on-all-spaces failed: $output")
            return false
        }
        return output.equals("true", ignoreCase = true)
    }
}
