package com.lucerna.core.render

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.Rgb
import com.lucerna.core.model.gradientEndpoints
import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*

/**
 * 把 [ColorFill] 画成位图。
 *
 * 壁纸、面板中的大预览、色彩卡片的缩略图都走这一份实现，
 * 保证三处看到的颜色完全一致。
 */
object ColorRenderer {

    fun render(fill: ColorFill, width: Int, height: Int): BufferedImage {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val normalized = fill.normalized()
        val image = BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            when (normalized.kind) {
                ColorKind.SOLID -> paintSolid(g, normalized.colorAt(0), safeWidth, safeHeight)
                ColorKind.GRADIENT -> paintGradient(g, normalized, safeWidth, safeHeight)
                ColorKind.STEPS -> paintSteps(g, normalized, safeWidth, safeHeight)
            }
        } finally {
            g.dispose()
        }
        return image
    }

    /** 渲染并写成 PNG，返回文件；宽度或高度非法时返回 null。 */
    fun writePng(fill: ColorFill, width: Int, height: Int, target: File): File? {
        if (width <= 0 || height <= 0) return null
        target.parentFile?.mkdirs()
        return runCatching {
            ImageIO.write(render(fill, width, height), "png", target)
            target
        }.getOrNull()
    }

    private fun paintSolid(g: Graphics2D, color: Rgb, width: Int, height: Int) {
        g.color = color.toAwt()
        g.fillRect(0, 0, width, height)
    }

    private fun paintGradient(g: Graphics2D, fill: ColorFill, width: Int, height: Int) {
        val count = fill.colors.size
        if (count < 2) {
            paintSolid(g, fill.colorAt(0), width, height)
            return
        }
        val (start, end) = gradientEndpoints(fill.angle, width.toFloat() / height)
        val fractions = FloatArray(count) { index -> index.toFloat() / (count - 1) }
        val colors = Array(count) { index -> fill.colors[index].toAwt() }
        val paint = LinearGradientPaint(
            start.x * width,
            start.y * height,
            end.x * width,
            end.y * height,
            fractions,
            colors,
            MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.paint = paint
        g.fillRect(0, 0, width, height)
    }

    /**
     * 色阶：把渐变均分后画成硬边色块。
     *
     * 每段色块用垂直于渐变方向的长条多边形填充，长条伸到画面之外，
     * 因此任意角度都不会留下没涂到的角。
     */
    private fun paintSteps(g: Graphics2D, fill: ColorFill, width: Int, height: Int) {
        val bands = fill.sampledSteps()
        val count = bands.size
        val radians = Math.toRadians(fill.angle.roundToIntMod360().toDouble())
        val ux = cos(radians)
        val uy = sin(radians)
        val vx = -uy
        val length = abs(width * ux) + abs(height * uy)
        val cx = width / 2.0
        val cy = height / 2.0
        val reach = hypot(width.toDouble(), height.toDouble()) * 2.0
        // 复用同一个路径对象，逐段改点即可，避免每段都新建 Path2D
        val path = Path2D.Double()

        for (index in bands.indices) {
            val t0 = index.toDouble() / count - 0.5
            val t1 = (index + 1).toDouble() / count - 0.5
            val p0x = cx + ux * length * t0
            val p0y = cy + uy * length * t0
            val p1x = cx + ux * length * t1
            val p1y = cy + uy * length * t1
            path.reset()
            path.moveTo(p0x + vx * reach, p0y + ux * reach)
            path.lineTo(p1x + vx * reach, p1y + ux * reach)
            path.lineTo(p1x - vx * reach, p1y - ux * reach)
            path.lineTo(p0x - vx * reach, p0y - ux * reach)
            path.closePath()
            g.color = bands[index].toAwt()
            g.fill(path)
        }
    }

    private fun Float.roundToIntMod360(): Int = this.roundToInt().mod(360)
}

/** Rgb 转 AWT 颜色，顺手做夹取。 */
fun Rgb.toAwt(): Color = Color(
    (r.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
    (g.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
    (b.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
)
