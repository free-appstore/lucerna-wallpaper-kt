package com.lucerna.app

import com.lucerna.core.platform.DesktopOs
import com.lucerna.ui.preview.PREVIEW_CHROME_HEIGHT
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

/** 计算面板与预览窗口的位置。 */
object PanelPlacement {

    private const val EDGE = 8

    /** 拿不到媒体尺寸时（例如尚未加载的视频）先按 16:9 占位。 */
    private const val DEFAULT_PREVIEW_ASPECT = 16f / 9f

    /** 面板贴在状态栏 / 任务栏下方，水平方向以鼠标位置（即托盘图标）为中心。 */
    fun panelBounds(width: Int, height: Int): Rectangle {
        val pointer = pointerLocation()
        val config = pointerScreenConfig(pointer)
        val screen = config.bounds
        // getScreenInsets 与 bounds 使用同一套 AWT 逻辑坐标；
        // macOS 某些缩放组合下会返回设备像素，这里按两倍值兜底
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val topInset = if (DesktopOs.current == DesktopOs.MACOS && insets.top > 48) insets.top / 2 else insets.top

        val desiredX = (pointer?.x ?: (screen.x + screen.width / 2)) - width / 2
        val minX = screen.x + EDGE
        val maxX = (screen.x + screen.width - width - EDGE).coerceAtLeast(minX)
        val x = desiredX.coerceIn(minX, maxX)
        val y = when {
            topInset > 0 -> screen.y + topInset + 6
            insets.bottom > 0 -> screen.y + screen.height - insets.bottom - height - 6
            else -> screen.y + EDGE
        }
        return Rectangle(x, y, width, height)
    }

    /**
     * 预览窗口：居中显示在当前（鼠标所在）屏幕，高度为屏幕的一半，
     * 中间的画面区域按媒体宽高比自适应。
     */
    fun previewBounds(mediaWidth: Int, mediaHeight: Int): Rectangle {
        val screen = pointerScreenConfig(pointerLocation()).bounds
        val size = previewWindowSize(screen.width, screen.height, mediaWidth, mediaHeight)
        return Rectangle(
            screen.x + (screen.width - size.width) / 2,
            screen.y + (screen.height - size.height) / 2,
            size.width,
            size.height,
        )
    }

    /**
     * 预览窗口在 [screenWidth] × [screenHeight] 屏幕上的尺寸。
     *
     * 窗口高度取屏幕的一半；扣掉标题栏与底栏后，画面区域高度 × 媒体宽高比 = 窗口宽度。
     * 超宽媒体按屏幕可用宽度收窄，同时把高度按同一比例收回，保证画面区域仍是媒体比例。
     */
    fun previewWindowSize(
        screenWidth: Int,
        screenHeight: Int,
        mediaWidth: Int,
        mediaHeight: Int,
    ): Dimension {
        val aspect = if (mediaWidth > 0 && mediaHeight > 0) {
            mediaWidth.toFloat() / mediaHeight
        } else {
            DEFAULT_PREVIEW_ASPECT
        }
        val windowHeight = (screenHeight / 2).coerceAtLeast(PREVIEW_CHROME_HEIGHT + 1)
        val contentHeight = windowHeight - PREVIEW_CHROME_HEIGHT
        val maxContentWidth = (screenWidth - 2 * EDGE).coerceAtLeast(1)
        val contentWidth = (contentHeight * aspect).roundToInt().coerceAtLeast(1)
        return if (contentWidth <= maxContentWidth) {
            Dimension(contentWidth, windowHeight)
        } else {
            val height = (maxContentWidth / aspect).roundToInt().coerceAtLeast(1)
            Dimension(maxContentWidth, height + PREVIEW_CHROME_HEIGHT)
        }
    }

    private fun pointerLocation(): Point? = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()

    /** 鼠标所在屏幕的配置；拿不到鼠标位置时退回主屏。 */
    private fun pointerScreenConfig(pointer: Point?): GraphicsConfiguration {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val device = environment.screenDevices.firstOrNull { device ->
            pointer != null && device.defaultConfiguration.bounds.contains(pointer)
        } ?: environment.defaultScreenDevice
        return device.defaultConfiguration
    }
}
