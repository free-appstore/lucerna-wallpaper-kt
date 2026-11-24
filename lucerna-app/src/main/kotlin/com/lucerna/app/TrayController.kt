package com.lucerna.app

import com.lucerna.core.model.TrayIconStyle
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

/**
 * 状态栏常驻图标。
 *
 * 图标按「萤火虫」的原始尺寸绘制，再等比放大到整块画布，让它在菜单栏里尽量占满高度。
 * 彩色模式是萤火虫本来的配色，单色模式是纯白剪影（菜单栏图标一般是白色）。
 */
class TrayController(
    initialStyle: TrayIconStyle = TrayIconStyle.COLOR,
    private val onToggle: () -> Unit,
) {
    private val tray = SystemTray.getSystemTray()
    private var lastFireAt = 0L
    private val icon = TrayIcon(createTrayImage(initialStyle), "Lucerna 灵萤").apply {
        isImageAutoSize = true
        addActionListener { fire() }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.button == java.awt.event.MouseEvent.BUTTON1) fire()
            }
        })
    }
    private var installed = false

    /** 切换菜单栏图标配色；图标已经装进菜单栏时立即重绘。 */
    fun setStyle(style: TrayIconStyle) {
        runCatching {
            icon.image = createTrayImage(style)
            println("[Lucerna] tray icon style=${style.label}")
        }.onFailure { println("[Lucerna] tray icon style failed: ${it.message}") }
    }

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
        /** 萤火虫的绘制尺寸（坐标系原点）。 */
        private const val DESIGN = 88

        /** 画布尺寸：按萤火虫的外接矩形裁好，画满整块，菜单栏里才显得大。 */
        private const val CANVAS_WIDTH = 100
        private const val CANVAS_HEIGHT = 88

        /** 萤火虫在 [DESIGN] 坐标系里的外接矩形（含描边）。 */
        private const val ART_LEFT = 0.055f
        private const val ART_TOP = 0.070f
        private const val ART_RIGHT = 0.945f
        private const val ART_BOTTOM = 0.860f

        /**
         * 画一只萤火虫：两片大翅膀、发光的腹部、头部与触角。
         *
         * [TrayIconStyle.COLOR] 用原本的暖色，[TrayIconStyle.MONO] 全部用纯白，
         * 菜单栏（尤其是深色半透明底色）上更清晰。
         */
        fun createTrayImage(style: TrayIconStyle): BufferedImage {
            val image = BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                // 把 DESIGN 坐标系里的萤火虫等比放大到整块画布（留 2px 余量给抗锯齿）
                val artWidth = (ART_RIGHT - ART_LEFT) * DESIGN
                val artHeight = (ART_BOTTOM - ART_TOP) * DESIGN
                val scale = minOf((CANVAS_WIDTH - 4f) / artWidth, (CANVAS_HEIGHT - 4f) / artHeight)
                g.scale(scale.toDouble(), scale.toDouble())
                g.translate(
                    ((CANVAS_WIDTH / scale - artWidth) / 2f - ART_LEFT * DESIGN).toDouble(),
                    ((CANVAS_HEIGHT / scale - artHeight) / 2f - ART_TOP * DESIGN).toDouble(),
                )

                drawFirefly(g, DESIGN.toFloat(), style)
            } finally {
                g.dispose()
            }
            return image
        }

        private fun drawFirefly(g: Graphics2D, size: Float, style: TrayIconStyle) {
            if (style == TrayIconStyle.MONO) {
                drawMonochromeFirefly(g, size)
            } else {
                drawColorFirefly(g, size)
            }
        }

        /**
         * 单色版：整体纯白，部件之间用「透明缝隙」分隔，
         * 这样既是纯白剪影，又能看清翅膀、腹部和头。
         */
        private fun drawMonochromeFirefly(g: Graphics2D, size: Float) {
            val cx = size / 2f
            val abdomen = Ellipse2D.Float(cx - size * 0.105f, size * 0.36f, size * 0.21f, size * 0.48f)
            val head = Ellipse2D.Float(cx - size * 0.080f, size * 0.25f, size * 0.16f, size * 0.16f)
            val wingWidth = size * 0.40f
            val wingHeight = size * 0.25f

            fun wing(mirror: Boolean): Pair<Ellipse2D.Float, Float> {
                val wx = if (mirror) cx + size * 0.19f else cx - size * 0.19f
                val wy = size * 0.40f
                return Ellipse2D.Float(wx - wingWidth / 2f, wy - wingHeight / 2f, wingWidth, wingHeight) to wx
            }

            fun rotated(degrees: Double, pivotX: Float, pivotY: Float, block: () -> Unit) {
                val old = g.transform
                g.rotate(Math.toRadians(degrees), pivotX.toDouble(), pivotY.toDouble())
                block()
                g.transform = old
            }

            fun fillWhite(shape: Shape) {
                g.color = Color.WHITE
                g.fill(shape)
            }

            fun gapStroke(width: Float) {
                g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            }

            /** 用透明描边挖出缝隙，图标整体仍然是纯白。 */
            fun clearStroke(shape: Shape, width: Float) {
                val old = g.composite
                g.composite = AlphaComposite.Clear
                gapStroke(width)
                g.draw(shape)
                g.composite = old
            }

            for (mirror in listOf(false, true)) {
                val (shape, wx) = wing(mirror)
                rotated(if (mirror) 32.0 else -32.0, wx, size * 0.40f) { fillWhite(shape) }
            }
            clearStroke(abdomen, size * 0.070f)
            fillWhite(abdomen)
            clearStroke(head, size * 0.060f)
            fillWhite(head)

            // 翅脉与腹部环节：透明细线，保留细节
            for (mirror in listOf(false, true)) {
                val (_, wx) = wing(mirror)
                val wy = size * 0.40f
                val old = g.transform
                g.rotate(Math.toRadians(if (mirror) 32.0 else -32.0), wx.toDouble(), wy.toDouble())
                val oldComposite = g.composite
                g.composite = AlphaComposite.Clear
                gapStroke(size * 0.018f)
                g.drawLine(
                    (wx - wingWidth * 0.30f).toInt(),
                    (wy + wingHeight * 0.18f).toInt(),
                    (wx + wingWidth * 0.34f).toInt(),
                    (wy - wingHeight * 0.16f).toInt(),
                )
                g.composite = oldComposite
                g.transform = old
            }
            val oldComposite = g.composite
            g.composite = AlphaComposite.Clear
            gapStroke(size * 0.018f)
            g.drawArc((cx - size * 0.10f).toInt(), (size * 0.44f).toInt(), (size * 0.20f).toInt(), (size * 0.16f).toInt(), 200, 140)
            g.drawArc((cx - size * 0.10f).toInt(), (size * 0.53f).toInt(), (size * 0.20f).toInt(), (size * 0.16f).toInt(), 200, 140)
            g.composite = oldComposite

            // 触角
            g.color = Color.WHITE
            gapStroke(size * 0.026f)
            g.drawLine((cx - size * 0.04f).toInt(), (size * 0.27f).toInt(), (size * 0.28f).toInt(), (size * 0.09f).toInt())
            g.drawLine((cx + size * 0.04f).toInt(), (size * 0.27f).toInt(), (size * 0.72f).toInt(), (size * 0.09f).toInt())
        }

        /** 彩色版：原本的暖色配色。 */
        private fun drawColorFirefly(g: Graphics2D, size: Float) {
            val outline = Color(0x3A, 0x27, 0x10)
            val wingColor = Color(0xF3, 0xB0, 0x55)
            val wingLight = Color(0xFF, 0xD9, 0x8A)
            val bodyColor = Color(0xFF, 0xC4, 0x6B)
            val bodyLight = Color(0xFF, 0xE8, 0xB5)
            val headColor = Color(0x4A, 0x30, 0x12)
            val segmentColor = Color(0xD9, 0x92, 0x33)

            val cx = size / 2f

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

            // 触角：用亮黄色，深色菜单栏上也看得清，稍加粗一档
            g.color = Color(0xFF, 0xE0, 0x8F)
            g.stroke = BasicStroke(size * 0.032f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine((cx - size * 0.04f).toInt(), (size * 0.27f).toInt(), (size * 0.28f).toInt(), (size * 0.09f).toInt())
            g.drawLine((cx + size * 0.04f).toInt(), (size * 0.27f).toInt(), (size * 0.72f).toInt(), (size * 0.09f).toInt())
        }
    }
}
