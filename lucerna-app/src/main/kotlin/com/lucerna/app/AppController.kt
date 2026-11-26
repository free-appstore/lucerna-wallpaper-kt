package com.lucerna.app

import com.lucerna.app.platform.MacSharedWallpaper
import com.lucerna.app.platform.MacWallpaperBackend
import com.lucerna.app.platform.SystemIntegration
import com.lucerna.core.actions.WallpaperActions
import com.lucerna.core.media.VideoPoster
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.VideoPlaybackSpeed
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperSource
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.platform.WallpaperBackend
import com.lucerna.core.render.ColorRenderer
import com.lucerna.core.store.LibraryStore
import com.lucerna.core.store.SettingsStore
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 把界面动作落到系统上。
 *
 * 所有壁纸都通过桌面壁纸层 / 系统壁纸接口设置，不创建普通窗口：
 * - 图片：直接设置原图
 * - 色彩：渲染成 PNG 后设置
 * - 视频：桌面层共享 AVPlayer 播放；系统 Desktop 用视频封面垫底，Idle（锁屏）保持不动
 *
 * 设置项由 [com.lucerna.ui.state.LucernaState] 先写进数据库再调用这里的动作，
 * 所以这里不再重复保存。只需要落盘的动作（登录启动、屏保、多空间）也由界面负责保存。
 */
class AppController(
    private val paths: AppPaths,
    private val settingsStore: SettingsStore,
    private val libraryStore: LibraryStore,
    private val backend: WallpaperBackend,
    private val onQuit: () -> Unit,
    private val onTrayIconStyle: (TrayIconStyle) -> Unit = {},
) : WallpaperActions {

    /** 设置壁纸要改壁纸库并重启 WallpaperAgent，放到后台线程，避免面板卡住。 */
    private val applyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lucerna-apply-wallpaper").apply { isDaemon = true }
    }

    /**
     * 连续点选壁纸时只让最后一次跑完：前面那些还没开始或正在解码的请求会被丢弃，
     * 解码时间留给最新点的那张。
     */
    private val applySeq = AtomicLong()

    fun restoreOnLaunch() {
        val settings = settingsStore.load()
        if (settings.disabled) return
        when (settings.currentSource) {
            WallpaperSource.COLOR -> applyColorInternal(settings.colorFill)
            WallpaperSource.LIBRARY -> currentLibraryItem(settings.currentWallpaperId)?.let { applyLibraryInternal(it) }
        }
    }

    override fun applyLibrary(item: WallpaperItem) {
        applyLibraryInternal(item)
    }

    override fun applyColor(fill: ColorFill) {
        applyColorInternal(fill)
    }

    override fun setVolume(volume: Float) = refreshVideoPlayback()

    override fun setAudioEnabled(enabled: Boolean) = refreshVideoPlayback()

    override fun setVideoPlaybackSpeed(speed: Float) = refreshVideoPlayback()

    override fun setDisabled(disabled: Boolean) {
        // 从禁用切回可用时，把当前壁纸重新铺起来（包括重新开始视频）
        if (disabled) refreshVideoPlayback() else restoreOnLaunch()
    }

    /** 设置已经由界面持久化，这里只把新参数推给正在播放的视频层。 */
    private fun refreshVideoPlayback() {
        val settings = settingsStore.load()
        val normalizedSpeed = VideoPlaybackSpeed.normalize(settings.videoSpeed)
        backend.updateVideoPlayback(
            volume = settings.volume,
            muted = !settings.audioEnabled,
            speed = if (normalizedSpeed <= 0f) 1f else normalizedSpeed,
            paused = settings.disabled || normalizedSpeed <= 0f,
        )
    }

    override fun setLaunchAtLogin(enabled: Boolean) {
        val ok = SystemIntegration.setLaunchAtLogin(enabled)
        log("launch at login = $enabled, ok=$ok")
    }

    override fun setScreensaverWallpaper(enabled: Boolean) = Unit

    override fun setAllSpacesShared(enabled: Boolean) {
        if (enabled) MacSharedWallpaper.ensure()
    }

    override fun setTrayIconStyle(style: TrayIconStyle) {
        onTrayIconStyle(style)
    }

    override fun openWallpaperSettings() {
        SystemIntegration.openWallpaperSettings()
    }

    override fun testScreensaver() {
        val ok = SystemIntegration.triggerScreensaver()
        log("trigger screensaver ok=$ok")
    }

    override fun openFileLocation(path: String) {
        SystemIntegration.reveal(path)
    }

    override fun openDirectory(path: String) {
        SystemIntegration.openDirectory(path)
    }

    override fun quit() {
        onQuit()
    }

    fun shutdown() {
        applyExecutor.shutdownNow()
        (backend as? MacWallpaperBackend)?.shutdown()
    }

    private fun applyLibraryInternal(item: WallpaperItem) {
        val seq = applySeq.incrementAndGet()
        applyExecutor.execute {
            // 已经点了下一张：这张连任务都不用跑
            if (seq != applySeq.get()) return@execute
            runCatching {
                when (item.type) {
                    WallpaperType.IMAGE -> {
                        val native = backend as? MacWallpaperBackend
                        val ok = if (native != null) {
                            native.applyImage(item.path) { seq != applySeq.get() }
                        } else {
                            backend.applyImage(item.path)
                        }
                        log("apply image ${item.name} -> $ok (${backend.name})")
                    }

                    WallpaperType.VIDEO -> {
                        val poster = ensureVideoPoster(item)
                        if (poster == null) {
                            log("video poster unavailable: ${item.name}")
                        } else if (seq == applySeq.get()) {
                            val settings = settingsStore.load()
                            val speed = VideoPlaybackSpeed.normalize(settings.videoSpeed)
                            val ok = backend.applyVideo(
                                videoPath = item.path,
                                posterPath = poster.absolutePath,
                                volume = settings.volume,
                                muted = !settings.audioEnabled,
                                speed = if (speed <= 0f) 1f else speed,
                                paused = settings.disabled || speed <= 0f,
                                isStale = { seq != applySeq.get() },
                            )
                            log("apply video ${item.name} -> $ok (${backend.name})")
                        }
                    }
                }
            }.onFailure { log("apply ${item.name} failed: ${it.message}") }
        }
    }

    private fun applyColorInternal(fill: ColorFill) {
        val seq = applySeq.incrementAndGet()
        applyExecutor.execute {
            if (seq != applySeq.get()) return@execute
            runCatching {
                val size = primarySize()
                val name = "color-${fill.hashCode()}-${size.width}x${size.height}.png"
                val target = paths.renderDir.resolve(name).toFile()
                // 同一份色彩已经渲染过就直接复用，拖动后重复「应用」不再重画整张 PNG
                val rendered = if (target.isFile && target.length() > 0) {
                    target
                } else {
                    ColorRenderer.writePng(fill, size.width, size.height, target)
                }
                if (rendered == null) {
                    log("render color failed")
                    return@runCatching
                }
                if (seq != applySeq.get()) return@runCatching
                val ok = backend.applyImage(rendered.absolutePath)
                log("apply color ${fill.summary()} -> $ok (${backend.name})")
            }.onFailure { log("apply color failed: ${it.message}") }
        }
    }

    /** 视频壁纸的静态封面；导入时已经预生成，这里通常直接命中缓存。 */
    private fun ensureVideoPoster(item: WallpaperItem): File? = VideoPoster.ensure(paths, item)

    private fun currentLibraryItem(id: String?): WallpaperItem? {
        if (id == null) return null
        return libraryStore.load().items.firstOrNull { it.id == id }
    }

    private fun primarySize(): java.awt.Dimension {
        val bounds = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .screenDevices
                .map { it.defaultConfiguration.bounds }
        }.getOrElse { emptyList() }
        if (bounds.isEmpty()) return java.awt.Dimension(2560, 1440)
        val width = bounds.maxOf { it.width }
        val height = bounds.maxOf { it.height }
        return java.awt.Dimension(width, height)
    }

    private fun log(message: String) {
        println("[Lucerna] $message")
    }
}
