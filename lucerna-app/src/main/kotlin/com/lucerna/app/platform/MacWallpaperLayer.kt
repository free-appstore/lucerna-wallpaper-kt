package com.lucerna.app.platform

import com.lucerna.core.platform.DesktopOs
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
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
    private class LayerWindow(val screen: Rectangle, val window: JWindow, val canvas: WallpaperCanvas)

    private val windows = ArrayList<LayerWindow>()
    private var currentImagePath: String? = null
    private var currentImage: BufferedImage? = null
    private var lastError: String? = null

    /** 每 2 秒检查一次屏幕布局：插上/拔掉显示器、改分辨率都会重建窗口，保证每块屏都有壁纸。 */
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

    /** 窗口层级（系统壁纸 +1，桌面图标以下）。 */
    val level: Int
        get() = MacNative.desktopLayerLevel()

    val error: String?
        get() = lastError

    fun isActive(): Boolean = windows.isNotEmpty() && windows.all { it.window.isVisible }

    /**
     * 把 [imagePath] 显示在每块屏幕上。第一次会创建窗口，之后只是换图。
     *
     * 返回 false 表示建窗口失败（例如系统不认桌面层级），调用方可以退回系统壁纸。
     */
    fun apply(imagePath: String): Boolean {
        if (DesktopOs.current != DesktopOs.MACOS) return false
        val source = load(imagePath) ?: return false

        var failure: String? = null
        runOnEdt {
            currentImagePath = imagePath
            currentImage = source
            val screens = screenBounds()
            if (screens.isEmpty()) {
                failure = "no screens"
                return@runOnEdt
            }
            if (!matchesScreens(screens)) rebuild(screens)
            windows.forEach {
                it.canvas.setWallpaper(source)
                it.window.repaint()
            }
            val unsupported = windows.count { !tune(it) }
            if (unsupported > 0) failure = "desktop layer unsupported on $unsupported window(s)"
        }

        lastError = failure
        if (failure == null) startWatching()
        return failure == null
    }

    /** 开始盯屏幕变化（只启动一次）。 */
    private fun startWatching() {
        if (watching) return
        watching = true
        runCatching {
            screenWatcher.scheduleWithFixedDelay(
                { runCatching { refreshScreens() } },
                2L,
                2L,
                TimeUnit.SECONDS,
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

    /** 屏幕数量/位置变了就整体重建；顺便补上没调好层级的窗口。 */
    fun refreshScreens() {
        if (DesktopOs.current != DesktopOs.MACOS) return
        runOnEdt {
            val screens = screenBounds()
            if (screens.isEmpty()) return@runOnEdt
            if (!matchesScreens(screens)) {
                println("[Lucerna] screens changed -> rebuild wallpaper layer for ${screens.size} screen(s)")
                rebuild(screens)
                val image = currentImage
                if (image != null) windows.forEach { it.canvas.setWallpaper(image) }
            } else {
                windows.forEach { tune(it) }
            }
        }
    }

    // --- 内部实现 ---

    private fun matchesScreens(screens: List<Rectangle>): Boolean =
        windows.size == screens.size && windows.indices.all { windows[it].screen == screens[it] }

    private fun rebuild(screens: List<Rectangle>) {
        windows.forEach { runCatching { it.window.dispose() } }
        windows.clear()
        screens.forEach { screen ->
            val canvas = WallpaperCanvas()
            val window = JWindow().apply {
                setFocusableWindowState(false)
                isAutoRequestFocus = false
                background = Color.BLACK
                contentPane.background = Color.BLACK
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
            tune(layer)
            window.isVisible = true
            // 显示后再设一次：AWT 在显示时会把层级重置成普通窗口
            tune(layer)
        }
    }

    /** 把 NSWindow 调成桌面层；失败返回 false。 */
    private fun tune(layer: LayerWindow): Boolean =
        MacNative.tuneAsDesktopLayer(layer.window) != null

    private fun screenBounds(): List<Rectangle> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
    }.getOrElse { emptyList() }

    private fun load(path: String): BufferedImage? = runCatching {
        if (path == currentImagePath) return currentImage
        val file = File(path)
        if (!file.isFile) return null
        ImageIO.read(file)
    }.getOrNull()

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

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val scale = max(width.toDouble() / source.width, height.toDouble() / source.height)
            val drawWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
            g2.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null)
        }
    }
}
