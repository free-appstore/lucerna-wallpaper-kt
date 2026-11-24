package com.lucerna.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 色彩壁纸的三种形态：纯色、平滑渐变、硬边色阶。
 */
@Serializable
enum class ColorKind {
    @SerialName("solid")
    SOLID,

    @SerialName("gradient")
    GRADIENT,

    @SerialName("steps")
    STEPS;

    /** 界面上显示的短标签。 */
    val label: String
        get() = when (this) {
            SOLID -> "纯色"
            GRADIENT -> "渐变"
            STEPS -> "色阶"
        }

    /** 纯色没有方向，只有渐变与色阶需要方向角。 */
    val usesAngle: Boolean
        get() = this != SOLID
}

/**
 * 一个 sRGB 颜色，三个分量都在 0..1 之间。
 */
@Serializable
data class Rgb(val r: Float, val g: Float, val b: Float) {

    fun normalized(): Rgb = Rgb(
        r = r.coerceIn(0f, 1f),
        g = g.coerceIn(0f, 1f),
        b = b.coerceIn(0f, 1f),
    )

    /** 在两色之间做线性插值，t 会被夹在 0..1。 */
    fun mix(other: Rgb, t: Float): Rgb {
        val f = t.coerceIn(0f, 1f)
        return Rgb(
            r = r + (other.r - r) * f,
            g = g + (other.g - g) * f,
            b = b + (other.b - b) * f,
        )
    }

    fun toHex(): String {
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
        return "#" + hex(ri) + hex(gi) + hex(bi)
    }

    companion object {
        val White = Rgb(1f, 1f, 1f)
        val Black = Rgb(0f, 0f, 0f)

        fun of(hex: Int): Rgb = Rgb(
            r = ((hex shr 16) and 0xFF) / 255f,
            g = ((hex shr 8) and 0xFF) / 255f,
            b = (hex and 0xFF) / 255f,
        )

        /** 解析 #RRGGBB 或 RRGGBB，失败时返回 null。 */
        fun parseHex(text: String): Rgb? {
            val clean = text.trim().removePrefix("#")
            if (clean.length != 6) return null
            val value = clean.toIntOrNull(16) ?: return null
            return of(value)
        }

        private fun hex(value: Int): String = value.coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    }
}

/**
 * 一条可以铺满整块屏幕的色彩填充。
 *
 * 角度以屏幕坐标度量（y 轴向下）：0° 从左到右，90° 从上到下，
 * 45° 沿 “\” 对角线，135° 沿 “/” 对角线。
 */
@Serializable
data class ColorFill(
    val kind: ColorKind = ColorKind.GRADIENT,
    val colors: List<Rgb> = DEFAULT_COLORS,
    val angle: Float = 90f,
    val steps: Int = 4,
) {
    /** 实际参与绘制的颜色数量，纯色只使用第一个。 */
    val wellCount: Int
        get() = if (kind == ColorKind.SOLID) 1 else colors.size

    /** 顶部卡片上的一行摘要，例如「色阶 · 3 色 · 6 阶」。 */
    fun summary(): String = when (kind) {
        ColorKind.SOLID -> "纯色"
        ColorKind.GRADIENT -> "渐变 · ${colors.size} 色"
        ColorKind.STEPS -> "色阶 · ${colors.size} 色 · $steps 阶"
    }

    fun colorAt(index: Int): Rgb = colors.getOrNull(index) ?: colors.lastOrNull() ?: Rgb.White

    fun canAddColor(): Boolean = kind != ColorKind.SOLID && colors.size < MAX_COLORS

    fun canRemoveColor(): Boolean = kind != ColorKind.SOLID && colors.size > 2

    fun withColor(index: Int, color: Rgb): ColorFill {
        if (index !in colors.indices) return this
        val next = colors.toMutableList()
        next[index] = color.normalized()
        return copy(colors = next)
    }

    fun addingColor(color: Rgb = colors.lastOrNull() ?: Rgb.White): ColorFill {
        if (!canAddColor()) return this
        return copy(colors = colors + color.normalized())
    }

    fun removingColor(index: Int): ColorFill {
        if (!canRemoveColor() || index !in colors.indices) return this
        val next = colors.toMutableList().apply { removeAt(index) }
        return copy(colors = next)
    }

    /** 把旧数据或连续操作后的状态收拢到合法范围。 */
    fun normalized(): ColorFill {
        val safeColors = colors
            .map { it.normalized() }
            .take(MAX_COLORS)
            .ifEmpty { listOf(Rgb.White) }
            .toMutableList()
        if (kind != ColorKind.SOLID) {
            // 渐变至少两个颜色；只有一个时复制一份，效果仍是纯色但不越界
            while (safeColors.size < 2) safeColors += safeColors.last()
        }
        val safeAngle = if (angle.isFinite()) angle.roundToInt().mod(360) else 90
        return copy(
            colors = safeColors,
            angle = safeAngle.toFloat(),
            steps = steps.coerceIn(MIN_STEPS, MAX_STEPS),
        )
    }

    /** 在渐变轴上按位置 t 取色，多个颜色之间线性插值。 */
    fun sample(t: Float): Rgb {
        if (colors.isEmpty()) return Rgb.White
        if (colors.size == 1) return colors[0]
        val span = (colors.size - 1).toFloat()
        val position = t.coerceIn(0f, 1f) * span
        val index = position.toInt().coerceIn(0, colors.size - 2)
        val local = (position - index).coerceIn(0f, 1f)
        return colors[index].mix(colors[index + 1], local)
    }

    /** 色阶段颜色：均分后取每段正中间的颜色。 */
    fun sampledSteps(): List<Rgb> {
        val count = steps.coerceIn(MIN_STEPS, MAX_STEPS)
        return (0 until count).map { index -> sample((index + 0.5f) / count) }
    }

    companion object {
        const val MAX_COLORS = 10
        const val MIN_STEPS = 2
        const val MAX_STEPS = 20

        /** 方向预设：→ ↓ ↘ ↗ */
        val ANGLE_PRESETS = listOf(0f, 90f, 45f, 135f)

        val DEFAULT_COLORS = listOf(
            Rgb(1.0f, 0.77f, 0.42f),
            Rgb(0.06f, 0.09f, 0.16f),
        )

        fun default(): ColorFill = ColorFill(
            kind = ColorKind.GRADIENT,
            colors = DEFAULT_COLORS,
            angle = 90f,
            steps = 4,
        )
    }
}

/** 渐变端点的归一化坐标（0..1），供 Compose 与 Java2D 共用。 */
data class GradientPoint(val x: Float, val y: Float)

/**
 * 根据方向角与区域宽高比计算渐变线两端。
 *
 * 渐变线长度取区域在该方向上的投影长度，斜角时也能覆盖两个角，
 * 不会在边角留下单色死区。宽高比 = 宽 / 高。
 */
fun gradientEndpoints(angle: Float, aspect: Float): Pair<GradientPoint, GradientPoint> {
    val radians = Math.toRadians(angle.roundToInt().mod(360).toDouble())
    val sin = sin(radians)
    val cos = cos(radians)
    val width = aspect.coerceAtLeast(0.001f).toDouble()
    val height = 1.0
    val length = abs(width * cos) + abs(height * sin)
    val dx = length * cos / (2.0 * width)
    val dy = length * sin / (2.0 * height)
    return GradientPoint((0.5 - dx).toFloat(), (0.5 - dy).toFloat()) to
        GradientPoint((0.5 + dx).toFloat(), (0.5 + dy).toFloat())
}
