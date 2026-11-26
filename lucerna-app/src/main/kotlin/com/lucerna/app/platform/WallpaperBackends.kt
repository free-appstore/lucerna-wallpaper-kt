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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
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
 * macOS：图片 / 色彩同时铺两处 ——
 * 1. **桌面壁纸层**（见 [MacWallpaperLayer]）：自己画在桌面层级的窗口里，换壁纸 = 换图，
 *    所有屏幕立刻生效、零灰屏；压在系统壁纸之上、桌面图标/程序/台前调度/程序坞之下，
 *    鼠标点击全部穿透给系统，并且出现在所有 Space 里。
 * 2. **系统壁纸**：同样设成这张图（当前 Space 用 NSWorkspace；壁纸库里每个 Space / 每块屏幕
 *    的配置也一起写，这一步会重启 WallpaperAgent，但灰屏被壁纸层盖住看不到）。
 *    Mission Control、切换桌面、全屏 Space 的快照都取系统壁纸，所以这一份必须也一致。
 *
 * 视频例外：桌面层播放用户视频；系统 Desktop 壁纸同步成同一视频封面，供 Space /
 * 调度中心过渡动画垫底。只写 Desktop，不动 Idle（锁屏）配置，系统动态壁纸/视频壁纸仍可在锁屏播放。
 *
 * 万一系统不认这个窗口层级（拿不到 NSWindow 或被系统策略挡住），退化为只用系统壁纸。
 */
class MacWallpaperBackend : WallpaperBackend {
    override val name = "macOS 桌面壁纸层"

    private val layer = MacWallpaperLayer()

    /**
     * 当前 Space 的系统壁纸：一次 NSWorkspace 调用（约 0.1 秒），单独一个线程，
     * 免得被「壁纸库同步」那种要重启 WallpaperAgent 的慢操作挡住。
     */
    private val currentSpaceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lucerna-wallpaper-current-space").apply { isDaemon = true }
    }

    @Volatile
    private var currentSpaceTarget: String? = null

    /**
     * 壁纸库（所有 Space 的系统壁纸）同步：要重启 WallpaperAgent，比较慢，
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

    override fun applyVideo(
        videoPath: String,
        posterPath: String,
        volume: Float,
        muted: Boolean,
        speed: Float,
        paused: Boolean,
        isStale: () -> Boolean,
    ): Boolean {
        // 桌面用 App 窗口播放；系统 Desktop 同步成视频封面，作为 Space/调度中心
        // 过渡动画的垫底；只写 Desktop，Idle（锁屏）仍由系统壁纸管理。
        val layerOk = layer.applyVideo(
            videoPath = videoPath,
            posterPath = posterPath,
            playback = VideoWallpaperPlayback(
                volume = volume,
                muted = muted,
                speed = speed,
                enabled = !paused,
            ),
            isStale = isStale,
        )
        if (layerOk) {
            // 桌面层正在播放；系统“桌面”这一份用封面垫底，让 Space / 调度中心过渡动画的
            // 第一帧至少是同一视频的画面，而不是旧系统壁纸。只写 Desktop 配置，
            // Idle（锁屏）保持用户自己的系统壁纸不动。
            if (!isStale()) requestCurrentSpace(posterPath)
            return true
        }

        // 桌面层不可用：退回静态封面，至少保证桌面能看见壁纸
        if (isStale()) return true
        return applyImage(posterPath)
    }

    override fun updateVideoPlayback(volume: Float, muted: Boolean, speed: Float, paused: Boolean) {
        layer.updatePlayback(
            VideoWallpaperPlayback(
                volume = volume,
                muted = muted,
                speed = speed,
                enabled = !paused,
            ),
        )
    }

    override fun applyImage(path: String): Boolean = applyImage(path) { false }

    /**
     * @param isStale 连续换壁纸时用来提前收工：新的请求马上会接手这张。
     */
    fun applyImage(path: String, isStale: () -> Boolean): Boolean {
        // 1) 桌面壁纸层：所有屏幕立刻换图（不重启服务、零灰屏），这是用户当下看到的那一层
        val layerOk = layer.apply(path, isStale)
        if (!layerOk) {
            println(
                "[Lucerna] 壁纸层不可用（${layer.error}），本轮只设置系统壁纸。" +
                    "如果是 AppKit 线程/反射问题，请确认 JVM 带了 --add-opens java.desktop/*=ALL-UNNAMED",
            )
        }

        // 2) 系统壁纸：壁纸层已经换好了，这一份不必挡住返回，排到后台线程去跟
        if (layerOk) {
            if (!isStale()) requestCurrentSpace(path)
            return true
        }

        // 壁纸层不可用时退回系统壁纸：这一份必须同步做完，调用方要用返回值判断结果
        val systemOk = MacNative.applyDesktopImage(path) || appleScript(path)
        requestStoreSync(path)
        return systemOk
    }

    /** 排队设置当前 Space 的系统壁纸，连续请求只保留最后一次。 */
    private fun requestCurrentSpace(path: String) {
        currentSpaceTarget = path
        currentSpaceExecutor.execute {
            if (currentSpaceTarget != path) return@execute
            val sync = {
                if (!MacNative.applyDesktopImage(path) && !appleScript(path)) {
                    println("[Lucerna] 当前 Space 的系统壁纸设置失败（壁纸层不受影响）")
                    false
                } else {
                    true
                }
            }
            // 打开「显示菜单栏背景」时，系统只在壁纸露出来时才给菜单栏重新采样。
            // 让开一条菜单栏高度（那条被菜单栏挡着，看不见），系统把壁纸设好后自己就跟上了；
            // 不再整屏让开，也就没有那一下闪烁。
            if (MacNative.menuBarBackgroundEnabled()) {
                layer.withMenuBarStripExposed(MENU_BAR_STRIP_HOLD_MS, sync) { currentSpaceTarget != path }
            } else {
                sync()
            }
            // 壁纸库同步会重启 WallpaperAgent，把当前 Space 刚画好的壁纸也一起重新加载。
            // 等菜单栏那边采样完再动壁纸库；即使当前 Space 设置失败，这一步也兜底。
            if (currentSpaceTarget == path) requestStoreSync(path)
        }
    }

    /** 排队同步壁纸库里所有 Space，连续请求只保留最后一次。 */
    private fun requestStoreSync(path: String) {
        storeSyncTarget = path
        storeSyncExecutor.execute {
            if (storeSyncTarget != path) return@execute
            val synced = MacWallpaperStore.applyToAllSpaces(path)
            if (!synced) println("[Lucerna] 系统壁纸的全桌面同步未完成（壁纸层不受影响）")
        }
    }

    /** 等排队中的系统壁纸同步跑完（冒烟测试收尾用）。 */
    fun awaitStoreSync(timeoutMs: Long = 15_000) {
        await(currentSpaceExecutor, timeoutMs)
        await(storeSyncExecutor, timeoutMs)
    }

    private fun await(executor: ExecutorService, timeoutMs: Long) {
        val done = CountDownLatch(1)
        runCatching { executor.execute { done.countDown() } }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        // 先把排队中的「当前 Space」与「壁纸库」同步做完，再销毁壁纸层：
        // 否则退出后系统壁纸会停在上一张（壁纸层已经不显示了）
        runCatching { awaitStoreSync(3000) }
        runCatching { currentSpaceExecutor.shutdownNow() }
        runCatching { storeSyncExecutor.shutdownNow() }
        layer.shutdown()
    }

    private fun appleScript(path: String): Boolean = runCatching {
        val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
        val script = "tell application \"System Events\" to tell every desktop to set picture to POSIX file \"$escaped\""
        ProcessBuilder("/usr/bin/osascript", "-e", script)
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    }.getOrElse { false }

    private companion object {
        /**
         * 系统壁纸设好后，让菜单栏后面那一条再露一会儿，等 macOS 重新采样菜单栏背景。
         * 露出的部分被菜单栏窗口挡着，用户看不到。
         */
        const val MENU_BAR_STRIP_HOLD_MS = 1200L
    }
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
