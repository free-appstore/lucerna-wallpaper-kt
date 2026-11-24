package com.lucerna.app

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

/**
 * 状态栏常驻图标。
 *
 * macOS 使用模板图（只读取 alpha 通道），深色菜单栏会自动反白；
 * Windows / Linux 直接显示同一张黑白萤火虫。
 */
class TrayController(
    private val onToggle: () -> Unit,
) {
    private val tray = SystemTray.getSystemTray()
    private var lastFireAt = 0L
    private val icon = TrayIcon(createTrayImage(), "Lucerna 灵萤").apply {
        isImageAutoSize = true
        addActionListener { fire() }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.button == java.awt.event.MouseEvent.BUTTON1) fire()
            }
        })
    }
    private var installed = false

    /** action 与 mouse 事件可能同时到达，250ms 内只处理一次。 */
    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 250) return
        lastFireAt = now
        onToggle()
    }

    fun install(): Boolean {
        if (!SystemTray.isSupported()) {
            println("[Lucerna] 系统不支持托盘图标，面板只能通过 LUCERNA_SMOKE=1 打开")
            return false
        }
        return runCatching {
            tray.add(icon)
            installed = true
            true
        }.getOrElse {
            println("[Lucerna] 托盘图标安装失败: ${it.message}")
            false
        }
    }

    fun remove() {
        if (!installed) return
        runCatching { tray.remove(icon) }
        installed = false
    }

    private companion object {
        /**
         * 画一只实心配色的萤火虫：两片大翅膀、发光的腹部、深色描边。
         * 所有颜色 alpha 都是 255，不使用半透明光晕。
         */
        fun createTrayImage(): BufferedImage {
            val size = 88
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val outline = Color(0x3A, 0x27, 0x10)
                val wingColor = Color(0xF3, 0xB0, 0x55)
                val wingLight = Color(0xFF, 0xD9, 0x8A)
                val bodyColor = Color(0xFF, 0xC4, 0x6B)
                val bodyLight = Color(0xFF, 0xE8, 0xB5)
                val headColor = Color(0x4A, 0x30, 0x12)
                val segmentColor = Color(0xD9, 0x92, 0x33)

                val cx = size / 2f
                val cy = size / 2f

                // 翅膀：左右各一片，向上展开；先画翅膀，身体压在上面
                fun wing(mirror: Boolean) {
                    val old = g.transform
                    val rotation = if (mirror) 32.0 else -32.0
                    val wx = if (mirror) cx + size * 0.19f else cx - size * 0.19f
                    val wy = size * 0.40f
                    val w = size * 0.40f
                    val h = size * 0.25f
                    g.rotate(Math.toRadians(rotation), wx.toDouble(), wy.toDouble())
                    val shape = Ellipse2D.Float(wx - w / 2f, wy - h / 2f, w, h)
                    g.color = wingColor
                    g.fill(shape)
                    g.color = outline
                    g.stroke = BasicStroke(size * 0.032f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(shape)
                    g.color = wingLight
                    g.stroke = BasicStroke(size * 0.020f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawLine(
                        (wx - w * 0.30f).toInt(),
                        (wy + h * 0.18f).toInt(),
                        (wx + w * 0.34f).toInt(),
                        (wy - h * 0.16f).toInt(),
                    )
                    g.transform = old
                }
                wing(mirror = false)
                wing(mirror = true)

                // 腹部：上窄下宽，底部用亮色表现荧光
                val abdomen = Ellipse2D.Float(cx - size * 0.105f, size * 0.36f, size * 0.21f, size * 0.48f)
                g.color = bodyColor
                g.fill(abdomen)
                g.color = outline
                g.stroke = BasicStroke(size * 0.030f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(abdomen)

                val glow = Ellipse2D.Float(cx - size * 0.070f, size * 0.55f, size * 0.14f, size * 0.24f)
                g.color = bodyLight
                g.fill(glow)

                // 腹部环节
                g.color = segmentColor
                g.stroke = BasicStroke(size * 0.020f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawArc((cx - size * 0.10f).toInt(), (size * 0.44f).toInt(), (size * 0.20f).toInt(), (size * 0.16f).toInt(), 200, 140)
                g.drawArc((cx - size * 0.10f).toInt(), (size * 0.53f).toInt(), (size * 0.20f).toInt(), (size * 0.16f).toInt(), 200, 140)

                // 头部
                val head = Ellipse2D.Float(cx - size * 0.080f, size * 0.25f, size * 0.16f, size * 0.16f)
                g.color = headColor
                g.fill(head)
                g.color = outline
                g.stroke = BasicStroke(size * 0.026f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(head)

                // 触角
                g.color = outline
                g.stroke = BasicStroke(size * 0.026f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine((cx - size * 0.04f).toInt(), (size * 0.27f).toInt(), (size * 0.28f).toInt(), (size * 0.09f).toInt())
                g.drawLine((cx + size * 0.04f).toInt(), (size * 0.27f).toInt(), (size * 0.72f).toInt(), (size * 0.09f).toInt())
            } finally {
                g.dispose()
            }
            return image
        }
    }
}
