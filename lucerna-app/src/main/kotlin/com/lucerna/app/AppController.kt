package com.lucerna.app

import com.lucerna.app.platform.MacSharedWallpaper
import com.lucerna.app.platform.SystemIntegration
import com.lucerna.core.actions.WallpaperActions
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.Settings
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperSource
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.platform.DesktopOs
import com.lucerna.core.platform.WallpaperBackend
import com.lucerna.core.render.ColorRenderer
import com.lucerna.core.store.LibraryStore
import com.lucerna.core.store.SettingsStore
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.Executors

/**
 * 把界面动作落到系统上。
 *
 * 所有壁纸都只通过系统壁纸接口设置，不创建额外的桌面窗口：
 * - 图片：直接设置原图
 * - 色彩：渲染成 PNG 后设置
 * - 视频：提取一帧封面后设置（本版本不播放动态壁纸，避免出现额外窗口）
 */
class AppController(
    private val paths: AppPaths,
    private val settingsStore: SettingsStore,
    private val libraryStore: LibraryStore,
    private val backend: WallpaperBackend,
    private val onQuit: () -> Unit,
    private val onTrayIconStyle: (TrayIconStyle) -> Unit = {},
) : WallpaperActions {

    private var settings: Settings = settingsStore.load()

    /** 设置壁纸要改壁纸库并重启 WallpaperAgent，放到后台线程，避免面板卡住。 */
    private val applyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lucerna-apply-wallpaper").apply { isDaemon = true }
    }

    fun restoreOnLaunch() {
        settings = settingsStore.load()
        if (settings.disabled) return
        when (settings.currentSource) {
            WallpaperSource.COLOR -> applyColorInternal(settings.colorFill)
            WallpaperSource.LIBRARY -> currentLibraryItem()?.let { applyLibraryInternal(it) }
        }
    }

    override fun applyLibrary(item: WallpaperItem) {
        settings = settingsStore.load()
        applyLibraryInternal(item)
    }

    override fun applyColor(fill: ColorFill) {
        settings = settingsStore.load()
        applyColorInternal(fill)
    }

    override fun setVolume(volume: Float) {
        settings = settings.copy(volume = volume.coerceIn(0f, 1f))
        settingsStore.save(settings)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        settings = settings.copy(audioEnabled = enabled)
        settingsStore.save(settings)
    }

    override fun setDisabled(disabled: Boolean) {
        settings = settingsStore.load().copy(disabled = disabled)
        settingsStore.save(settings)
    }

    override fun setLaunchAtLogin(enabled: Boolean) {
        settings = settings.copy(launchAtLogin = enabled)
        settingsStore.save(settings)
        val ok = SystemIntegration.setLaunchAtLogin(enabled)
        log("launch at login = $enabled, ok=$ok")
    }

    override fun setScreensaverWallpaper(enabled: Boolean) {
        settings = settings.copy(screensaverWallpaper = enabled)
        settingsStore.save(settings)
    }

    override fun setAllSpacesShared(enabled: Boolean) {
        settings = settings.copy(allSpacesShared = enabled)
        settingsStore.save(settings)
        if (enabled) MacSharedWallpaper.ensure()
    }

    override fun setTrayIconStyle(style: TrayIconStyle) {
        settings = settingsStore.load().copy(trayIconStyle = style)
        settingsStore.save(settings)
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
        (backend as? com.lucerna.app.platform.MacWallpaperBackend)?.shutdown()
    }

    private fun applyLibraryInternal(item: WallpaperItem) {
        applyExecutor.execute {
            runCatching {
                when (item.type) {
                    WallpaperType.IMAGE -> {
                        val ok = backend.applyImage(item.path)
                        log("apply image ${item.name} -> $ok (${backend.name})")
                    }

                    WallpaperType.VIDEO -> {
                        val poster = ensureVideoPoster(item)
                        if (poster == null) {
                            log("video poster unavailable: ${item.name}")
                        } else {
                            val ok = backend.applyImage(poster.absolutePath)
                            log("apply video poster ${item.name} -> $ok (${backend.name})")
                        }
                    }
                }
            }.onFailure { log("apply ${item.name} failed: ${it.message}") }
        }
    }

    private fun applyColorInternal(fill: ColorFill) {
        applyExecutor.execute {
            runCatching {
                val size = primarySize()
                val name = "color-${fill.hashCode()}-${size.width}x${size.height}.png"
                val target = paths.renderDir.resolve(name).toFile()
                val rendered = ColorRenderer.writePng(fill, size.width, size.height, target)
                if (rendered == null) {
                    log("render color failed")
                    return@runCatching
                }
                val ok = backend.applyImage(rendered.absolutePath)
                log("apply color ${fill.summary()} -> $ok (${backend.name})")
            }.onFailure { log("apply color failed: ${it.message}") }
        }
    }

    /**
     * 为视频生成一帧封面，写入 renders/posters 后复用。
     *
     * macOS 用系统自带的 Quick Look，其他平台尝试 ffmpeg；两者都不可用时返回 null。
     */
    private fun ensureVideoPoster(item: WallpaperItem): File? {
        val source = File(item.path)
        if (!source.isFile) return null
        val dir = paths.renderDir.resolve("posters").toFile().apply { mkdirs() }
        val target = File(dir, "${item.id}.png")
        if (target.isFile && target.length() > 0) return target

        val ok = when (DesktopOs.current) {
            DesktopOs.MACOS -> runQuickLook(source, dir, target)
            else -> runFfmpegPoster(source, target)
        }
        return target.takeIf { ok && it.isFile && it.length() > 0 }
    }

    private fun runQuickLook(source: File, dir: File, target: File): Boolean {
        val commandOk = runCommand(
            "qlmanage", "-t", "-s", "1920", "-o", dir.absolutePath, source.absolutePath,
        )
        if (!commandOk) return false
        val generated = File(dir, source.name + ".png")
        if (!generated.isFile) return false
        return generated.renameTo(target) || runCatching {
            generated.copyTo(target, overwrite = true)
            generated.delete()
            true
        }.getOrElse { false }
    }

    private fun runFfmpegPoster(source: File, target: File): Boolean = runCommand(
        "ffmpeg", "-y", "-ss", "1", "-i", source.absolutePath, "-frames:v", "1", target.absolutePath,
    )

    private fun runCommand(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrElse { false }

    private fun currentLibraryItem(): WallpaperItem? {
        val id = settings.currentWallpaperId ?: return null
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
