package com.lucerna.app.platform

import com.lucerna.core.platform.DesktopOs
import com.lucerna.core.platform.WallpaperBackend
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/** 按平台选择系统壁纸实现。 */
object PlatformWallpapers {
    fun create(): WallpaperBackend = when (DesktopOs.current) {
        DesktopOs.MACOS -> MacWallpaperBackend()
        DesktopOs.WINDOWS -> WindowsWallpaperBackend()
        DesktopOs.LINUX -> LinuxWallpaperBackend()
        DesktopOs.UNKNOWN -> NoopWallpaperBackend
    }
}

/**
 * macOS：先把图片设到所有连接的屏幕，再把壁纸库里每个桌面（每个 Space、每块屏幕）
 * 都改成这张图 —— 调度中心里没显示出来的桌面也必须一起换。
 */
class MacWallpaperBackend : WallpaperBackend {
    override val name = "macOS 桌面图片"

    override fun applyImage(path: String): Boolean {
        // 1) 当前显示的每块屏幕立刻生效（NSWorkspace 只作用于当前 Space，所以只是第一步）
        val applied = MacNative.applyDesktopImage(path) || appleScript(path)
        // 2) 壁纸库里所有 Space / 所有屏幕一起改，避免切桌面、退出全屏后又看到旧壁纸
        val synced = MacWallpaperStore.applyToAllSpaces(path)
        if (!synced) {
            println("[Lucerna] all-desktop sync unavailable, only the visible desktops were updated")
        }
        return applied || synced
    }

    private fun appleScript(path: String): Boolean = runCatching {
        val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
        val script = "tell application \"System Events\" to tell every desktop to set picture to POSIX file \"$escaped\""
        ProcessBuilder("/usr/bin/osascript", "-e", script)
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    }.getOrElse { false }
}

/** Windows：SystemParametersInfo 设置壁纸，并用注册表指定「填充」样式。 */
class WindowsWallpaperBackend : WallpaperBackend {
    override val name = "Windows 桌面壁纸"

    override fun applyImage(path: String): Boolean = runCatching {
        // WallpaperStyle = 10 表示填充，TileWallpaper = 0 表示不平铺
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, "Control Panel\\Desktop", "WallpaperStyle", 10)
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, "Control Panel\\Desktop", "TileWallpaper", 0)
        val wide = Memory((path.length + 1) * 2L)
        wide.setWideString(0, path)
        WinUser32.INSTANCE.SystemParametersInfoW(
            SPI_SETDESKWALLPAPER,
            0,
            wide,
            SPIF_UPDATEINIFILE or SPIF_SENDCHANGE,
        )
    }.getOrElse { false }

    private interface WinUser32 : Library {
        fun SystemParametersInfoW(uiAction: Int, uiParam: Int, pvParam: Pointer, fWinIni: Int): Boolean

        companion object {
            val INSTANCE: WinUser32 by lazy { Native.load("user32", WinUser32::class.java) }
        }
    }

    private companion object {
        const val SPI_SETDESKWALLPAPER = 20
        const val SPIF_UPDATEINIFILE = 0x01
        const val SPIF_SENDCHANGE = 0x02
    }
}

/**
 * Linux：按桌面环境依次尝试。
 *
 * GNOME / Unity 用 gsettings，KDE 用 plasma-apply-wallpaperimage，
 * XFCE 用 xfconf-query，最后兜底 feh / nitrogen。
 */
class LinuxWallpaperBackend : WallpaperBackend {
    override val name = "Linux 桌面壁纸"

    override fun applyImage(path: String): Boolean {
        val uri = File(path).toURI().toString()
        val desktop = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").lowercase()
        val attempts = buildList {
            if ("kde" in desktop || "plasma" in desktop) {
                add(listOf("plasma-apply-wallpaperimage", path))
            }
            if ("gnome" in desktop || "unity" in desktop || "cinnamon" in desktop || "pantheon" in desktop || desktop.isBlank()) {
                add(listOf("gsettings", "set", "org.gnome.desktop.background", "picture-uri", uri))
                add(listOf("gsettings", "set", "org.gnome.desktop.background", "picture-uri-dark", uri))
                add(listOf("gsettings", "set", "org.gnome.desktop.background", "picture-options", "zoom"))
            }
            if ("xfce" in desktop) {
                add(listOf("xfconf-query", "-c", "xfce4-desktop", "-p", "/backdrop/screen0/monitor0/workspace0/last-image", "-s", path))
            }
            add(listOf("feh", "--bg-fill", path))
            add(listOf("nitrogen", "--set-zoom-fill", "--save", path))
        }
        return attempts.any { command -> runCatching { ProcessBuilder(command).start().waitFor() == 0 }.getOrElse { false } }
    }
}

/** 不支持的平台：保持系统壁纸不变。 */
object NoopWallpaperBackend : WallpaperBackend {
    override val name = "未支持"
    override fun applyImage(path: String): Boolean = false
}
