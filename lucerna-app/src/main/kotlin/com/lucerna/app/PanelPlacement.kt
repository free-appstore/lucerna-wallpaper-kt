package com.lucerna.app

import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit

/** 计算面板与预览窗口的位置。 */
object PanelPlacement {

    private const val EDGE = 8

    /** 面板贴在状态栏 / 任务栏下方，水平方向以鼠标位置（即托盘图标）为中心。 */
    fun panelBounds(width: Int, height: Int): Rectangle {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        val device = environment.screenDevices.firstOrNull { device ->
            pointer != null && device.defaultConfiguration.bounds.contains(pointer)
        } ?: environment.defaultScreenDevice
        val config = device.defaultConfiguration
        val screen = config.bounds
        // getScreenInsets 与 bounds 使用同一套 AWT 逻辑坐标；
        // macOS 某些缩放组合下会返回设备像素，这里按两倍值兜底
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val topInset = if (com.lucerna.core.platform.DesktopOs.current == com.lucerna.core.platform.DesktopOs.MACOS && insets.top > 48) {
            insets.top / 2
        } else {
            insets.top
        }

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

    fun centeredBounds(width: Int, height: Int): Rectangle {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        val device = environment.screenDevices.firstOrNull { device ->
            pointer != null && device.defaultConfiguration.bounds.contains(pointer)
        } ?: environment.defaultScreenDevice
        val screen = device.defaultConfiguration.bounds
        val x = screen.x + (screen.width - width) / 2
        val y = screen.y + (screen.height - height) / 2
        return Rectangle(x, y, width, height)
    }
}
