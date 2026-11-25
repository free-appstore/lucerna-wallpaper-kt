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

/**
 * 桌面壁纸层：不用系统壁纸服务，直接把壁纸画在每一块屏幕的「桌面层级」窗口里。
 *
 * 每个窗口：层级在系统壁纸之上、桌面图标之下；鼠标事件全部穿透给系统；
 * 出现在所有 Space（切桌面不闪、不重建）；没有阴影、不抢焦点。
 * 所以换壁纸只是换窗口里的图片 —— 不重启任何系统服务，也就没有灰屏。
 */
internal class MacWallpaperLayer {

    /** 一块屏幕上的壁纸窗口。 */
    private class LayerWindow(val screen: Rectangle, val window: JWindow, val canvas: WallpaperCanvas) {
        /** 层级是否已经调好；没调好的窗口是隐藏的，巡检时再补一次。 */
        var tuned = false
    }

    private val windows = ArrayList<LayerWindow>()

    @Volatile
    private var currentImagePath: String? = null

    @Volatile
    private var currentImage: BufferedImage? = null

    @Volatile
    private var lastError: String? = null

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

        startWatching()
        // CGWindowList 解析要几十毫秒，放在换图之后做：此时 EDT 已经在画新壁纸了。
        // 已经被新请求取代时直接跳过，把时间留给下一张。
        if (!isStale()) hideCoveredScreens()
        return true
    }

    /** 把一张已经解好的图换到所有窗口上；第一次还会补建/调层级。 */
    private fun showOnScreen(imagePath: String, image: BufferedImage): Boolean {
        var failure: String? = null
        runOnEdt {
            currentImagePath = imagePath
            currentImage = image
            val screens = screenBounds()
            if (screens.isEmpty()) {
                failure = "no screens"
                return@runOnEdt
            }
            if (!matchesScreens(screens)) rebuild(screens, image)
            // 登记重绘后立即返回，真正的绘制在 EDT 的下一次重绘里
            windows.forEach { it.canvas.setWallpaper(image) }
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

    /**
     * 某块屏幕被全屏 app 占着时，把这层窗口藏起来 —— 无论如何都不会盖住全屏 app。
     *
     * 全屏 Space 里本来也看不到桌面，而系统壁纸是同一张图，所以藏起来用户完全无感；
     * 退出全屏后（下一轮巡检，最快 0.8 秒）窗口再显示出来。
     */
    private fun hideCoveredScreens() {
        val covered = runCatching { MacFullscreenDetector.fullscreenScreens() }.getOrElse { emptyList() }
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
            windows.forEach { runCatching { it.window.dispose() } }
            windows.clear()
            currentImagePath = null
            currentImage = null
        }
    }

    /** 屏幕巡检：布局变了就重建，否则只补调层级；最后同步全屏显隐。必须在 EDT 上调用。 */
    private fun refreshLayout(screens: List<Rectangle>, covered: List<Rectangle>) {
        if (DesktopOs.current != DesktopOs.MACOS) return
        if (!matchesScreens(screens)) {
            println("[Lucerna] screens changed -> rebuild wallpaper layer for ${screens.size} screen(s)")
            rebuild(screens, currentImage)
            val image = currentImage
            if (image != null) windows.forEach { it.canvas.setWallpaper(image) }
        } else {
            // 已经调好的窗口不必每秒重新反射 + 切 AppKit 线程
            windows.forEach { if (!it.tuned) tune(it) }
        }
        applyVisibility(covered)
    }

    /** 被全屏 app 覆盖的屏幕隐藏壁纸窗，其余显示。必须在 EDT 上调用。 */
    private fun applyVisibility(covered: List<Rectangle>) {
        windows.forEach { layer ->
            val shouldShow = covered.none { it == layer.screen }
            if (layer.window.isVisible != shouldShow) {
                layer.window.isVisible = shouldShow
                // 显隐会让 AWT 把窗口层级重置成普通窗口，下次显示时要重新调
                layer.tuned = false
                if (shouldShow) tune(layer)
            }
        }
    }

    // --- 内部实现 ---

    private fun matchesScreens(screens: List<Rectangle>): Boolean =
        windows.size == screens.size && windows.indices.all { windows[it].screen == screens[it] }

    private fun rebuild(screens: List<Rectangle>, image: BufferedImage?) {
        windows.forEach { runCatching { it.window.dispose() } }
        windows.clear()
        // 首帧还没画上图时窗口底色会露出来：用这张图的平均色垫底，比黑色闪烁轻得多
        val tint = image?.averageColor() ?: Color.BLACK
        screens.forEach { screen ->
            val canvas = WallpaperCanvas().apply { setWallpaper(image) }
            val window = JWindow().apply {
                setFocusableWindowState(false)
                isAutoRequestFocus = false
                background = tint
                contentPane.background = tint
                contentPane.layout = java.awt.BorderLayout()
                contentPane.add(canvas, java.awt.BorderLayout.CENTER)
                setBounds(screen)
                // 先把 NSWindow 建出来但不显示（addNotify），把层级和「加入所有 Space」设好再显示。
                // 否则窗口会先被绑到「当前 Space」：在全屏 app 占着这块屏幕时建窗口，
                // 回到桌面 Space 后这个窗口就看不到，那块屏就会漏出系统壁纸。
                runCatching { addNotify() }
            }
            val layer = LayerWindow(screen, window, canvas)
            windows.add(layer)
            val tunedBeforeShow = tune(layer)
            window.isVisible = true
            // EDT 正忙（例如刚启动面板）时首帧可能来不及画，同步补一次，别让底色停留
            runCatching { canvas.paintImmediately(0, 0, canvas.width, canvas.height) }
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
