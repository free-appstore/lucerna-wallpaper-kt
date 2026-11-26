package com.lucerna.core

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.Rgb
import com.lucerna.core.model.gradientEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorFillTest {

    @Test
    fun `色阶数量与采样位置`() {
        val fill = ColorFill(
            kind = ColorKind.STEPS,
            colors = listOf(Rgb.Black, Rgb.White),
            steps = 4,
        ).normalized()
        val bands = fill.sampledSteps()
        assertEquals(4, bands.size)
        assertTrue(kotlin.math.abs(bands[0].r - 0.125f) < 1e-4)
        assertTrue(kotlin.math.abs(bands[3].r - 0.875f) < 1e-4)
    }

    @Test
    fun `渐变端点与预设角度一致`() {
        val (start, end) = gradientEndpoints(0f, 1.5f)
        assertTrue(kotlin.math.abs(start.x) < 1e-4 && kotlin.math.abs(start.y - 0.5f) < 1e-4)
        assertTrue(kotlin.math.abs(end.x - 1f) < 1e-4 && kotlin.math.abs(end.y - 0.5f) < 1e-4)

        val (top, bottom) = gradientEndpoints(90f, 1.5f)
        assertTrue(kotlin.math.abs(top.x - 0.5f) < 1e-4 && kotlin.math.abs(top.y) < 1e-4)
        assertTrue(kotlin.math.abs(bottom.x - 0.5f) < 1e-4 && kotlin.math.abs(bottom.y - 1f) < 1e-4)
    }

    @Test
    fun `归一化会补齐颜色并夹取参数`() {
        val minimal = ColorFill(
            kind = ColorKind.GRADIENT,
            colors = listOf(Rgb(2f, -1f, 0.5f)),
            angle = 180.4f,
            steps = 99,
        ).normalized()
        assertEquals(2, minimal.colors.size)
        assertEquals(Rgb(1f, 0f, 0.5f), minimal.colors[0])
        assertEquals(180f, minimal.angle)
        assertEquals(ColorFill.MAX_STEPS, minimal.steps)
    }

    @Test
    fun `颜色增删遵守上下限`() {
        var fill = ColorFill.default()
        assertEquals(2, fill.colors.size)
        assertTrue(fill.addingColor().colors.size == 3)
        assertEquals(2, fill.removingColor(0).colors.size)
        fill = fill.copy(colors = List(ColorFill.MAX_COLORS) { Rgb.White })
        assertTrue(!fill.canAddColor())
        assertEquals(ColorFill.MAX_COLORS, fill.addingColor().colors.size)
    }

    @Test
    fun `纯色只显示一格`() {
        val fill = ColorFill.default().copy(kind = ColorKind.SOLID, colors = listOf(Rgb.of(0xFF0000)))
        assertEquals(1, fill.wellCount)
        assertEquals("纯色", fill.summary())
    }

    @Test
    fun `色阶阶数不少于颜色数量`() {
        val colors = List(5) { Rgb.of(0x336699) }

        // 旧数据或手输更小的阶数时，归一化会抬到颜色数量
        assertEquals(5, ColorFill(kind = ColorKind.STEPS, colors = colors, steps = 2).normalized().steps)

        // 加色后阶数跟着涨，不会出现颜色比阶数多
        val grown = ColorFill(kind = ColorKind.STEPS, colors = colors.take(2), steps = 2).addingColor()
        assertEquals(3, grown.colors.size)
        assertEquals(3, grown.steps)

        // 渐变没有这个下限
        assertEquals(2, ColorFill(kind = ColorKind.GRADIENT, colors = colors, steps = 2).normalized().steps)
    }

    @Test
    fun `十六进制解析`() {
        assertEquals(Rgb.of(0xFFC46B), Rgb.parseHex("#FFC46B"))
        assertEquals(Rgb.of(0x0A0B0C), Rgb.parseHex("0a0b0c"))
        assertEquals(null, Rgb.parseHex("oops"))
    }
}
