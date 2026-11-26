package com.lucerna.app.platform

import com.lucerna.core.platform.DesktopOs
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import javax.swing.JComponent
import javax.swing.JWindow
import kotlin.math.max
import kotlin.math.roundToInt

/** 视频壁纸的运行时参数。 */
internal data class VideoWallpaperPlayback(
    val volume: Float = 0f,
    val muted: Boolean = true,
    val speed: Float = 1f,
    val enabled: Boolean = true,
)

/**
 * 桌面壁纸层：不用系统壁纸服务，直接把壁纸画在每一块屏幕的「桌面层级」窗口里。
 *
 * 每个窗口：层级在系统壁纸之上、桌面图标之下；鼠标事件全部穿透给系统；
 * 出现在所有 Space（切桌面不闪、不重建）；没有阴影、不抢焦点。
 * 图片是窗口里的 AWT canvas；视频是原生 AVPlayerLayer，所有屏幕共享同一个 AVPlayer。
 */
internal class MacWallpaperLayer {

    /** 一块屏幕上的壁纸窗口；图片用 [canvas]，视频用 [video]，两者只会有其一。 */
    private class LayerWindow(
        val screen: Rectangle,
        val window: JWindow,
        val canvas: WallpaperCanvas?,
        val video: Boolean,
    ) {
        /** 层级是否已经调好；没调好的窗口是隐藏的，巡检时再补一次。 */
        var tuned = false

        fun dispose() {
            runCatching { window.dispose() }
        }
    }

    private val windows = ArrayList<LayerWindow>()

    @Volatile
    private var currentImagePath: String? = null

    @Volatile
    private var currentImage: BufferedImage? = null

    /** 当前视频路径；非空时窗口里是视频。 */
    @Volatile
    private var currentVideoPath: String? = null

    /** 视频的静态封面，用来给窗口垫底。 */
    @Volatile
    private var currentPoster: BufferedImage? = null

    @Volatile
    private var playback = VideoWallpaperPlayback()

    /** 所有屏幕共享的 AVFoundation 播放器；只在视频壁纸期间存在。 */
    @Volatile
    private var videoSession: MacAvVideo.Session? = null

    @Volatile
    private var lastError: String? = null

    /** 在全屏 Space 里换过壁纸后，切回桌面时检查窗口是否真的还在屏上。 */
    @Volatile
    private var checkOnReveal = false

    /** 上一次巡检时是否有屏幕被全屏 app 覆盖。 */
    @Volatile
    private var wasCovered = false

    /** 每 0.8 秒检查一次屏幕布局：插上/拔掉显示器、改分辨率都会重建窗口，保证每块屏都有壁纸。 */
    private val screenWatcher = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lucerna-wallpaper-screens").apply { isDaemon = true }
    }

    @Volatile
    private var watching = false

    /** 当前显示的图片路径，冒烟测试用来回读。 */
    val imagePath: String?
        get() = currentImagePath

    /** 已经建好几块屏幕的窗口。 */
    val windowCount: Int
        get() = windows.size

    val error: String?
        get() = lastError

    fun isActive(): Boolean = windows.isNotEmpty() && windows.all { it.window.isVisible }

    /**
     * 把 [imagePath] 显示在每块屏幕上。第一次会创建窗口，之后只是换图。
     *
     * 返回 false 表示建窗口失败（例如系统不认桌面层级），调用方可以退回系统壁纸。
     */
    fun apply(imagePath: String): Boolean = apply(imagePath) { false }

    /**
     * @param isStale 新的一次换壁纸已经开始排队时返回 true：这张就不上屏了，交给新请求。
     */
    fun apply(imagePath: String, isStale: () -> Boolean): Boolean {
        if (DesktopOs.current != DesktopOs.MACOS) return false
        val cached = currentImage
        if (imagePath == currentImagePath && cached != null) {
            // 同一张图（例如重复点同一张壁纸）：不解码，直接确保窗口在显示
            if (!showOnScreen(imagePath, cached)) return false
        } else {
            if (isStale()) return true
            val image = load(imagePath) ?: return false
            // 解码期间又点了别的壁纸：这张就不换了，解码时间让给新请求
            if (isStale()) return true
            if (!showOnScreen(imagePath, image)) return false
        }
        disposeVideoSession()

        startWatching()
        // CGWindowList 解析要几十毫秒，放在换图之后做：此时 EDT 已经在画新壁纸了。
        // 已经被新请求取代时直接跳过，把时间留给下一张。
        if (!isStale()) refreshCoverage(markRevealCheck = true)
        return true
    }

    /**
     * 在桌面层里播放视频壁纸。
     *
     * 一块屏幕一个 AVPlayerLayer，但三块屏共享一个 AVPlayer：只解码一次、同一帧、同一进度。
     * [posterPath] 用作窗口首帧垫底，也由后端写进系统 Desktop 壁纸，供 Space/调度中心过渡动画使用；
     * 系统 Idle（锁屏）配置不动。
     */
    fun applyVideo(
        videoPath: String,
        posterPath: String,
        playback: VideoWallpaperPlayback,
        isStale: () -> Boolean = { false },
    ): Boolean {
        if (DesktopOs.current != DesktopOs.MACOS) return false
        if (!MacAvVideo.supports(videoPath)) {
            println("[Lucerna] native AVPlayer does not support ${File(videoPath).extension}; fallback to poster")
            return false
        }
        this.playback = playback

        if (currentVideoPath == videoPath && windows.isNotEmpty() && videoSession != null) {
            runOnEdt { updateVideoPlayback() }
            startWatching()
            refreshCoverage(markRevealCheck = true)
            return true
        }

        if (isStale()) return true
        val poster = load(posterPath)
        if (isStale()) return true

        val initialRate = if (playback.enabled && playback.speed > 0f) playback.speed else 0f
        val session = MacAvVideo.open(videoPath, initialRate, playback.volume, playback.muted) ?: run {
            println("[Lucerna] native AVPlayer failed to open $videoPath")
            return false
        }
        if (isStale()) {
            MacAvVideo.dispose(session)
            return true
        }
        val oldSession = videoSession
        videoSession = session

        var failure: String? = null
        runOnEdt {
            currentVideoPath = videoPath
            currentPoster = poster
            currentImagePath = null
            currentImage = null
            val screens = screenBounds()
            if (screens.isEmpty()) {
                failure = "no screens"
                return@runOnEdt
            }
            rebuild(screens)
            updateVideoPlayback()
            val unsupported = windows.count { it.window.isVisible && !tune(it) }
            if (unsupported > 0) failure = "desktop layer unsupported on $unsupported window(s)"
        }
        oldSession?.let(MacAvVideo::dispose)
        lastError = failure
        startWatching()
        if (!isStale()) refreshCoverage(markRevealCheck = true)
        return failure == null
    }

    /** 倍速 / 音量 / 暂停等参数变化时调用，不影响当前视频的播放位置。 */
    fun updatePlayback(playback: VideoWallpaperPlayback) {
        this.playback = playback
        runOnEdt { updateVideoPlayback() }
    }

    private fun updateVideoPlayback() {
        val session = videoSession ?: return
        // 全屏 app / 调度中心覆盖时也继续保持播放：
        // 一是不让过渡动画里的视频落后或停住，二是避免全屏检测在动画期间反复改 rate 造成卡顿。
        val anyVisible = windows.any { it.video && it.window.isVisible }
        val rate = if (playback.enabled && anyVisible && playback.speed > 0f) playback.speed else 0f
        MacAvVideo.update(session, rate, playback.volume, playback.muted)
    }

    private fun disposeVideoSession() {
        val session = videoSession ?: return
        videoSession = null
        MacAvVideo.dispose(session)
    }

    /** 把一张已经解好的图换到所有窗口上；第一次还会补建/调层级。 */
    private fun showOnScreen(imagePath: String, image: BufferedImage): Boolean {
        var failure: String? = null
        runOnEdt {
            val wasVideo = currentVideoPath != null
            currentVideoPath = null
            currentPoster = null
            currentImagePath = imagePath
            currentImage = image
            val screens = screenBounds()
            if (screens.isEmpty()) {
                failure = "no screens"
                return@runOnEdt
            }
            if (wasVideo || !matchesScreens(screens)) rebuild(screens)
            // 登记重绘后立即返回，真正的绘制在 EDT 的下一次重绘里
            windows.forEach { it.canvas?.setWallpaper(image) }
            val unsupported = windows.count { it.window.isVisible && !tune(it) }
            if (unsupported > 0) failure = "desktop layer unsupported on $unsupported window(s)"
        }
        lastError = failure
        return failure == null
    }

    /**
     * 换壁纸时把有菜单栏的屏幕的壁纸窗口顶部裁掉一条菜单栏的高度（窗口本身不动、不缩放），
     * 只露出「菜单栏后面」那一条系统壁纸，拿着 [block] 做完后再等 [holdMs] 毫秒恢复。
     *
     * macOS 只在系统壁纸真正露到屏幕上时才给菜单栏背景重新采样；壁纸层一直盖着桌面的话，
     * 菜单栏会停在上一张。裁出来的这一条完全被菜单栏窗口挡着，用户看不到，
     * 所以顶部能立刻跟上新壁纸，又不会像整屏让开那样闪一下。
     *
     * 只是改窗口的形状，不重建绘制表面，也不动画面和窗口位置，桌面一个像素都不会跳。
     */
    fun <T> withMenuBarStripExposed(holdMs: Long, block: () -> T, isStale: () -> Boolean = { false }): T {
        if (DesktopOs.current != DesktopOs.MACOS) return block()
        val screens = screenBounds()
        if (screens.isEmpty()) return block()
        val insets = runCatching { MacFullscreenDetector.menuBarInsets(screens) }.getOrElse { emptyMap() }
        val opened = ArrayList<LayerWindow>()
        if (insets.isNotEmpty()) {
            runOnEdt {
                windows.forEach { layer ->
                    val inset = insets[layer.screen] ?: return@forEach
                    if (!layer.window.isVisible || inset >= layer.screen.height) return@forEach
                    runCatching {
                        layer.window.shape = Rectangle(
                            0,
                            inset,
                            layer.screen.width,
                            layer.screen.height - inset,
                        )
                        opened.add(layer)
                    }
                }
            }
        }

        try {
            return block()
        } finally {
            if (opened.isNotEmpty()) {
                // 这段时间露出的壁纸被菜单栏挡着；多留一会儿，保证系统采样完成再盖回去。
                // 期间又点了别的壁纸就提前收起，把时间让给下一张。
                val deadline = System.currentTimeMillis() + holdMs
                while (System.currentTimeMillis() < deadline && !isStale()) {
                    runCatching { Thread.sleep(50) }
                }
                runOnEdt {
                    opened.forEach { layer -> runCatching { layer.window.shape = null } }
                }
            }
        }
    }

    /** 刷新全屏状态：全屏 app 占着屏幕时只暂停视频，不隐藏壁纸窗。 */
    private fun refreshCoverage(markRevealCheck: Boolean = false) {
        val covered = runCatching { MacFullscreenDetector.fullscreenScreens() }.getOrElse { emptyList() }
        if (markRevealCheck && covered.isNotEmpty()) checkOnReveal = true
        runOnEdt { applyVisibility(covered) }
    }

    /** 开始盯屏幕变化（只启动一次）。 */
    private fun startWatching() {
        if (watching) return
        watching = true
        runCatching {
            screenWatcher.scheduleWithFixedDelay(
                {
                    // 屏幕枚举与全屏检测都在巡检线程上做，EDT 只负责改窗口
                    runCatching {
                        val screens = screenBounds()
                        if (screens.isEmpty()) return@runCatching
                        val covered = MacFullscreenDetector.fullscreenScreens(screens)
                        runOnEdt { refreshLayout(screens, covered) }
                    }
                },
                800L,
                800L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun shutdown() {
        watching = false
        runCatching { screenWatcher.shutdownNow() }
        runOnEdt {
            windows.forEach { it.dispose() }
            windows.clear()
            currentImagePath = null
            currentImage = null
            currentVideoPath = null
            currentPoster = null
        }
        disposeVideoSession()
    }

    /** 屏幕巡检：布局变了就重建，否则只补调层级；最后刷新全屏状态。必须在 EDT 上调用。 */
    private fun refreshLayout(screens: List<Rectangle>, covered: List<Rectangle>) {
        if (DesktopOs.current != DesktopOs.MACOS) return
        if (!matchesScreens(screens)) {
            println("[Lucerna] screens changed -> rebuild wallpaper layer for ${screens.size} screen(s)")
            rebuild(screens)
            val image = currentImage
            if (image != null) windows.forEach { it.canvas?.setWallpaper(image) }
        } else {
            // 已经调好的窗口不必每秒重新反射 + 切 AppKit 线程
            windows.forEach { if (!it.tuned) tune(it) }
        }
        applyVisibility(covered)
    }

    /**
     * 全屏 app / 调度中心覆盖时既**不隐藏窗口，也不暂停共享播放器**。
     *
     * 隐藏再显示会让 AppKit 把桌面层窗口移出当前 Space；暂停播放又会让过渡动画里的
     * 视频停在旧帧，或在全屏检测抖动时反复 rate 0/1 造成一卡一卡。
     * 桌面层通过 fullScreenAuxiliary 进入全屏 Space 的过渡快照，层级又低于全屏 app，
     * 所以让它一直播放既安全，也能让调度中心 / 切桌面的动画直接看到当前视频。
     *
     * 必须在 EDT 上调用。
     */
    private fun applyVisibility(covered: List<Rectangle>) {
        val nowCovered = covered.isNotEmpty()
        if (wasCovered && !nowCovered) checkOnReveal = true
        wasCovered = nowCovered

        // 切出全屏后，AppKit 有时会把桌面层窗口移出当前 Space：
        // isVisible 仍是 true，WindowServer 里却已经不在屏上。这里做一次实际校验，
        // 只在真的缺窗口时重建，避免每次全屏退出都闪一下。
        if (checkOnReveal && !nowCovered) {
            checkOnReveal = false
            val screens = screenBounds()
            val level = MacNative.desktopLayerLevel()
            val onScreen = MacFullscreenDetector.ownWindows().count { it.level == level && it.onScreen }
            if (windows.size != screens.size || onScreen < screens.size) {
                println(
                    "[Lucerna] wallpaper layer missing after fullscreen " +
                        "(windows=${windows.size}, onscreen=$onScreen, screens=${screens.size}) -> rebuild",
                )
                rebuild(screens)
            }
        }
        windows.forEach { layer ->
            if (!layer.window.isVisible) {
                // 只有调层级失败的窗口会处于隐藏状态；层级恢复后再显示，失败则继续隐藏
                if (tune(layer)) {
                    layer.window.isVisible = true
                    layer.tuned = false
                    if (!tune(layer)) layer.window.isVisible = false
                }
            } else if (!layer.tuned) {
                tune(layer)
            }
        }
        updateVideoPlayback()
    }

    // --- 内部实现 ---

    private fun matchesScreens(screens: List<Rectangle>): Boolean =
        windows.size == screens.size && windows.indices.all { windows[it].screen == screens[it] }

    private fun rebuild(screens: List<Rectangle>) {
        windows.forEach { it.dispose() }
        windows.clear()
        val videoPath = currentVideoPath
        val image = currentImage
        // 首帧还没画出来时窗口底色会露出来：用图片 / 视频封面的平均色垫底，比黑色闪烁轻得多
        val tint = (image ?: currentPoster)?.averageColor() ?: Color.BLACK
        screens.forEach { screen ->
            val window = JWindow().apply {
                setFocusableWindowState(false)
                isAutoRequestFocus = false
                background = tint
                contentPane.background = tint
                contentPane.layout = java.awt.BorderLayout()
                setBounds(screen)
            }
            val canvas: WallpaperCanvas?
            val video: Boolean
            if (videoPath != null) {
                canvas = null
                video = true
            } else {
                canvas = WallpaperCanvas().apply { setWallpaper(image) }
                video = false
                window.contentPane.add(canvas, java.awt.BorderLayout.CENTER)
            }
            // 先把 NSWindow 建出来但不显示（addNotify），把层级和「加入所有 Space」设好再显示。
            // 否则窗口会先被绑到「当前 Space」：在全屏 app 占着这块屏幕时建窗口，
            // 回到桌面 Space 后这个窗口就看不到，那块屏就会漏出系统壁纸。
            runCatching { window.addNotify() }

            val layer = LayerWindow(screen, window, canvas, video)
            windows.add(layer)
            val tunedBeforeShow = tune(layer)
            window.isVisible = true
            if (video) {
                // 一块屏幕一个 AVPlayerLayer，但共用同一个 AVPlayer；视频只解码一次
                val session = videoSession
                val attached = session?.let { MacAvVideo.attach(it, window) }
                if (attached == null) {
                    println("[Lucerna] native video layer attach failed on ${screen.width}x${screen.height}")
                    window.isVisible = false
                }
            }
            // EDT 正忙（例如刚启动面板）时首帧可能来不及画，同步补一次，别让底色停留
            if (canvas != null) {
                runCatching { canvas.paintImmediately(0, 0, canvas.width, canvas.height) }
            }
            // 显示后再设一次：AWT 在显示时会把层级重置成普通窗口
            layer.tuned = false
            val tunedAfterShow = tune(layer)
            if (!tunedAfterShow) {
                // 调不成桌面层就是一张普通全屏窗口，会把所有 App（包括全屏的）盖住 ——
                // 宁可不显示，也不能遮挡别人；调用方会退回系统壁纸
                println("[Lucerna] wallpaper layer window not tuned (before=$tunedBeforeShow) -> hidden")
                window.isVisible = false
            }
        }
    }

    /** 壁纸的平均色，窗口首帧画出来之前用来垫底。 */
    private fun BufferedImage.averageColor(): Color {
        val stepX = (width / 16).coerceAtLeast(1)
        val stepY = (height / 16).coerceAtLeast(1)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        var y = stepY / 2
        while (y < height) {
            var x = stepX / 2
            while (x < width) {
                val rgb = getRGB(x, y)
                red += (rgb shr 16) and 0xFF
                green += (rgb shr 8) and 0xFF
                blue += rgb and 0xFF
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return Color.BLACK
        return Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    /** 把 NSWindow 调成桌面层；失败返回 false（调用方会把窗口藏起来，绝不遮挡别人）。 */
    private fun tune(layer: LayerWindow): Boolean {
        if (layer.tuned) return true
        if (MacNative.tuneAsDesktopLayer(layer.window) == null) return false
        layer.tuned = true
        return true
    }

    private fun screenBounds(): List<Rectangle> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
    }.getOrElse { emptyList() }

    /** 所有屏幕里最大的设备像素尺寸（retina 按 2 倍算），解码只需要到这个大小。 */
    private fun maxScreenPixels(): Dimension = runCatching {
        var width = 0
        var height = 0
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEach { device ->
            val config = device.defaultConfiguration
            val bounds = config.bounds
            val transform = config.defaultTransform
            width = max(width, (bounds.width * transform.scaleX).roundToInt())
            height = max(height, (bounds.height * transform.scaleY).roundToInt())
        }
        Dimension(width, height)
    }.getOrElse { Dimension(0, 0) }

    private fun load(path: String): BufferedImage? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        val screen = maxScreenPixels()
        if (screen.width <= 0 || screen.height <= 0) ImageIO.read(file)
        else readForScreen(file, screen.width, screen.height)
    }.getOrNull()

    /**
     * 只解到「刚好覆盖最大屏幕」的分辨率：按整数倍抽样解码，内存占用降到几分之一
     * （一张 8K 图整张展开要 130MB 左右），像素少一些绘制也稍快。
     */
    private fun readForScreen(file: File, screenWidth: Int, screenHeight: Int): BufferedImage? {
        var input: ImageInputStream? = null
        var reader: ImageReader? = null
        return try {
            input = ImageIO.createImageInputStream(file) ?: return null
            reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) return null
            // 抽样后的图仍要盖满屏幕，取两个方向缩比里较小的那个（整数倍）
            val subsample = max(1, minOf(width / screenWidth, height / screenHeight))
            if (subsample <= 1) reader.read(0) else readSubsampled(reader, subsample)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { reader?.dispose() }
            runCatching { input?.close() }
        }
    }

    /** 按整数倍抽样解码；少数插件不支持抽样，退回整张读取。 */
    private fun readSubsampled(reader: ImageReader, subsample: Int): BufferedImage? {
        val param = reader.defaultReadParam
        param.setSourceSubsampling(subsample, subsample, 0, 0)
        return runCatching { reader.read(0, param) }.getOrElse { reader.read(0) }
    }

    private fun runOnEdt(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeAndWait(block)
    }

    /** 画壁纸：等比铺满（cover），居中裁掉多余部分。 */
    private class WallpaperCanvas : JComponent() {
        private var image: BufferedImage? = null

        fun setWallpaper(next: BufferedImage?) {
            image = next
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val source = image ?: return
            val g2 = g as Graphics2D
            g2.color = Color.BLACK
            g2.fillRect(0, 0, width, height)
            if (width <= 0 || height <= 0) return

            // 源图已经解到屏幕分辨率附近，双线性插值就够了；不要求 QUALITY，避免某些
            // 渲染管线在首帧挑更慢的缩放路径
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val scale = max(width.toDouble() / source.width, height.toDouble() / source.height)
            val drawWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
            g2.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null)
        }
    }
}
