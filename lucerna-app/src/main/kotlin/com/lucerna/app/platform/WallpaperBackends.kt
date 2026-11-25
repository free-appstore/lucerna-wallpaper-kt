package com.lucerna.app.platform

import com.lucerna.core.platform.DesktopOs
import com.lucerna.core.platform.WallpaperBackend
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.GraphicsEnvironment
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
 * macOS：不用系统壁纸服务，直接把壁纸画在「桌面层级」的窗口里（见 [MacWallpaperLayer]）。
 *
 * 窗口压在系统壁纸之上、桌面图标/程序/台前调度/程序坞之下，鼠标点击全部穿透给系统，
 * 并且出现在所有 Space 里，所以换壁纸 = 换窗口里的图片：不重启任何服务，没有灰屏，
 * 所有屏幕、所有桌面同时生效。
 *
 * 万一系统不认这个窗口层级（拿不到 NSWindow 或被系统策略挡住），自动退回系统壁纸。
 */
class MacWallpaperBackend : WallpaperBackend {
    override val name = "macOS 桌面壁纸层"

    private val layer = MacWallpaperLayer()

    /** 桌面壁纸层是否在工作（冒烟测试回读用）。 */
    val layerActive: Boolean get() = layer.isActive()

    val layerImagePath: String? get() = layer.imagePath

    val layerWindowCount: Int get() = layer.windowCount

    val layerLevel: Int get() = layer.level

    val layerScreens: Int get() = macScreenCount()

    override fun applyImage(path: String): Boolean {
        if (layer.apply(path)) {
            // 系统壁纸同步成同一张，作为「垫底」：调度中心 / 切空间的过渡动画是 macOS 用
            // Space 快照合成的，桌面层窗口不在那份快照里，动画那一两帧只会画系统壁纸。
            // 这里只有一次 NSWorkspace 调用（约 0.1 秒，不重启服务、不闪屏），
            // 平时被壁纸层完全盖住，只有动画漏出来的瞬间才看得到同样的图。
            MacNative.applyDesktopImage(path)
            return true
        }
        val reason = layer.error ?: "unknown"
        println(
            "[Lucerna] 壁纸层不可用（$reason），退回系统壁纸。" +
                "若提示 AppKit 线程，请确认 JVM 带了 --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
        return applySystemWallpaper(path)
    }

    /** 屏幕数量/位置变化时重建壁纸窗口。 */
    fun refreshScreens() = layer.refreshScreens()

    fun shutdown() = layer.shutdown()

    /** 系统壁纸兜底路径（只在桌面层建不起来时用）：会把壁纸库也写一遍，保证每个桌面都换掉。 */
    private fun applySystemWallpaper(path: String): Boolean {
        val applied = MacNative.applyDesktopImage(path) || appleScript(path)
        val synced = MacWallpaperStore.applyToAllSpaces(path)
        return applied || synced
    }

    private fun macScreenCount(): Int = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .size

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
