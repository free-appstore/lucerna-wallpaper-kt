package com.lucerna.app.platform

import com.lucerna.core.platform.DesktopOs
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** 系统集成：文件定位、登录启动、屏保测试。 */
object SystemIntegration {

    private const val LOGIN_LABEL = "app.lucerna.wallpaper"

    fun reveal(path: String): Boolean {
        val file = File(path)
        return when (DesktopOs.current) {
            DesktopOs.MACOS -> exec("/usr/bin/open", "-R", file.absolutePath) ||
                exec("/usr/bin/open", file.absoluteFile.parent)

            DesktopOs.WINDOWS -> exec("explorer.exe", "/select,${file.absolutePath}")

            DesktopOs.LINUX -> exec("xdg-open", file.absoluteFile.parent)

            DesktopOs.UNKNOWN -> false
        }
    }

    fun openDirectory(path: String): Boolean {
        val dir = File(path)
        return when (DesktopOs.current) {
            DesktopOs.MACOS -> exec("/usr/bin/open", dir.absolutePath)
            DesktopOs.WINDOWS -> exec("explorer.exe", dir.absolutePath)
            DesktopOs.LINUX -> exec("xdg-open", dir.absolutePath)
            DesktopOs.UNKNOWN -> false
        }
    }

    fun openWallpaperSettings() {
        when (DesktopOs.current) {
            DesktopOs.MACOS -> exec("open", "x-apple.systempreferences:com.apple.Wallpaper-Settings.extension")
            DesktopOs.WINDOWS -> exec("cmd", "/c", "start", "ms-settings:personalization-background")
            else -> exec("xdg-open", "gnome-control-center")
        }
    }

    fun setLaunchAtLogin(enabled: Boolean): Boolean = when (DesktopOs.current) {
        DesktopOs.MACOS -> setMacLoginItem(enabled)
        DesktopOs.WINDOWS -> setWindowsLoginItem(enabled)
        DesktopOs.LINUX -> setLinuxLoginItem(enabled)
        DesktopOs.UNKNOWN -> false
    }

    fun triggerScreensaver(): Boolean = when (DesktopOs.current) {
        DesktopOs.MACOS -> exec("/usr/bin/open", "-a", "ScreenSaverEngine")
        DesktopOs.WINDOWS -> exec("rundll32.exe", "user32.dll,LockWorkStation")
        DesktopOs.LINUX ->
            exec("xdg-screensaver", "activate") ||
                exec("gnome-screensaver-command", "-a") ||
                exec("xscreensaver-command", "-activate")
        DesktopOs.UNKNOWN -> false
    }

    private fun exec(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrElse { false }

    private fun setMacLoginItem(enabled: Boolean): Boolean {
        val agentDir = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents")
        val plist = agentDir.resolve("$LOGIN_LABEL.plist")
        if (!enabled) {
            return runCatching { Files.deleteIfExists(plist) }.isSuccess
        }
        val java = currentJava()
        val classpath = System.getProperty("java.class.path").orEmpty()
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key><string>$LOGIN_LABEL</string>
                <key>ProgramArguments</key>
                <array>
                    <string>${xmlEscape(java)}</string>
                    <string>-cp</string>
                    <string>${xmlEscape(classpath)}</string>
                    <string>com.lucerna.app.MainKt</string>
                </array>
                <key>RunAtLoad</key><true/>
                <key>ProcessType</key><string>Interactive</string>
            </dict>
            </plist>
        """.trimIndent()
        return runCatching {
            Files.createDirectories(agentDir)
            Files.writeString(plist, xml)
            true
        }.getOrElse { false }
    }

    private fun setWindowsLoginItem(enabled: Boolean): Boolean = runCatching {
        val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        if (enabled) {
            val command = "\"${currentJava()}\" -cp \"${System.getProperty("java.class.path").orEmpty()}\" com.lucerna.app.MainKt"
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "Lucerna", command)
        } else {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, "Lucerna")
        }
        true
    }.getOrElse { false }

    private fun setLinuxLoginItem(enabled: Boolean): Boolean {
        val autostart = Path.of(System.getProperty("user.home"), ".config", "autostart")
        val entry = autostart.resolve("lucerna-wallpaper.desktop")
        if (!enabled) {
            return runCatching { Files.deleteIfExists(entry) }.isSuccess
        }
        val java = currentJava()
        val classpath = System.getProperty("java.class.path").orEmpty()
        val content = """
            [Desktop Entry]
            Type=Application
            Name=Lucerna
            Comment=灵萤 · 桌面壁纸
            Exec="$java" -cp "$classpath" com.lucerna.app.MainKt
            Terminal=false
            X-GNOME-Autostart-enabled=true
        """.trimIndent()
        return runCatching {
            Files.createDirectories(autostart)
            Files.writeString(entry, content)
            true
        }.getOrElse { false }
    }

    private fun currentJava(): String =
        ProcessHandle.current().info().command().orElse(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
        )

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
