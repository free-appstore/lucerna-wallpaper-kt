package com.lucerna.app.platform

import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * 冒烟测试用的假全屏窗口：盖住整块屏幕，连菜单栏区域一起，模拟微信这类全屏 app。
 *
 * 它跑在独立子进程里，所以 [MacFullscreenDetector] 会把这块窗口当成第三方全屏 app，
 * 从而走「同步壁纸库里所有 Space」的真实分支。
 */
internal object FakeFullscreenWindow {

    fun show(seconds: Int, screenIndex: Int) {
        val shown = CountDownLatch(1)
        SwingUtilities.invokeLater {
            val screens = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .screenDevices
                    .map { it.defaultConfiguration.bounds }
            }.getOrElse { emptyList() }
            val target = screens.getOrNull(screenIndex)
                ?: screens.firstOrNull()
                ?: Rectangle(0, 0, 1280, 800)

            val frame = JFrame("Lucerna smoke fullscreen")
            frame.isUndecorated = true
            frame.background = Color(0x15, 0x16, 0x1A)
            frame.layout = GridBagLayout()
            frame.add(
                JLabel("Lucerna smoke: 模拟全屏 app", SwingConstants.CENTER).apply {
                    foreground = Color(0xF2, 0xF3, 0xF7)
                },
            )
            frame.setBounds(target)
            frame.isVisible = true
            frame.isAlwaysOnTop = true
            frame.toFront()
            println("[Lucerna] fake fullscreen window=${frame.bounds} target=$target")
            shown.countDown()
        }
        shown.await(5, TimeUnit.SECONDS)
        Thread.sleep(seconds * 1000L)
    }
}
