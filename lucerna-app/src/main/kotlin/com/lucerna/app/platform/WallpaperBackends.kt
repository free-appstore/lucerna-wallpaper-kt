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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 * macOS：壁纸同时铺两处 ——
 * 1. **桌面壁纸层**（见 [MacWallpaperLayer]）：自己画在桌面层级的窗口里，换壁纸 = 换图，
 *    所有屏幕立刻生效、零灰屏；压在系统壁纸之上、桌面图标/程序/台前调度/程序坞之下，
 *    鼠标点击全部穿透给系统，并且出现在所有 Space 里。
 * 2. **系统壁纸**：同样设成这张图（当前 Space 用 NSWorkspace；壁纸库里每个 Space / 每块屏幕
 *    的配置也一起写，这一步会重启 WallpaperAgent，但灰屏被壁纸层盖住看不到）。
 *    Mission Control、切换桌面、全屏 Space 的快照都取系统壁纸，所以这一份必须也一致。
 *
 * 万一系统不认这个窗口层级（拿不到 NSWindow 或被系统策略挡住），退化为只用系统壁纸。
 */
class MacWallpaperBackend : WallpaperBackend {
    override val name = "macOS 桌面壁纸层"

    private val layer = MacWallpaperLayer()

    /**
     * 壁纸库（所有 Space 的系统壁纸）同步放在独立线程里做：它要重启 WallpaperAgent，比较慢，
     * 而且连续换壁纸时只有最后一张有意义 —— 队列里过期的请求直接丢掉。
     */
    private val storeSyncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lucerna-wallpaper-store").apply { isDaemon = true }
    }

    @Volatile
    private var storeSyncTarget: String? = null

    /** 桌面壁纸层是否在工作（冒烟测试回读用）。 */
    val layerActive: Boolean get() = layer.isActive()

    val layerImagePath: String? get() = layer.imagePath

    val layerWindowCount: Int get() = layer.windowCount

    val layerLevel: Int get() = layer.level

    val layerScreens: Int get() = macScreenCount()

    override fun applyImage(path: String): Boolean {
        // 1) 桌面壁纸层：所有屏幕立刻换图（不重启服务、零灰屏），这是用户当下看到的那一层
        val layerOk = layer.apply(path)
        if (!layerOk) {
            println(
                "[Lucerna] 壁纸层不可用（${layer.error}），本轮只设置系统壁纸。" +
                    "如果是 AppKit 线程/反射问题，请确认 JVM 带了 --add-opens java.desktop/*=ALL-UNNAMED",
            )
        }

        // 2) 系统壁纸（当前 Space）：一次 NSWorkspace 调用，不重启服务
        val systemOk = MacNative.applyDesktopImage(path) || appleScript(path)

        // 3) 系统壁纸（壁纸库里每个 Space / 每块屏幕）：Mission Control、切换桌面、
        //    全屏 Space 的快照都取系统壁纸，所以这一份也要一起更新。
        //    这一步会重启 WallpaperAgent，但桌面已经被壁纸层盖住，灰屏看不到。
        requestStoreSync(path)

        return layerOk || systemOk
    }

    /** 排队同步「所有 Space 的系统壁纸」，连续请求只保留最后一个。 */
    private fun requestStoreSync(path: String) {
        storeSyncTarget = path
        storeSyncExecutor.execute {
            if (storeSyncTarget != path) return@execute
            val synced = MacWallpaperStore.applyToAllSpaces(path)
            if (!synced) println("[Lucerna] 系统壁纸的全桌面同步未完成（壁纸层不受影响）")
        }
    }

    /** 等排队中的「所有 Space 系统壁纸同步」跑完（冒烟测试收尾用）。 */
    fun awaitStoreSync(timeoutMs: Long = 15_000) {
        val done = CountDownLatch(1)
        runCatching { storeSyncExecutor.execute { done.countDown() } }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 屏幕数量/位置变化时重建壁纸窗口。 */
    fun refreshScreens() = layer.refreshScreens()

    fun shutdown() {
        // 退出前把还在排队的那次「系统壁纸同步」做完，别让系统壁纸停在上一张
        runCatching { awaitStoreSync(3000) }
        runCatching { storeSyncExecutor.shutdownNow() }
        layer.shutdown()
    }

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
