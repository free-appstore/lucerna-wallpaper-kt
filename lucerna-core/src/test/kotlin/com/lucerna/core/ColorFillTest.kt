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
    fun `十六进制解析`() {
        assertEquals(Rgb.of(0xFFC46B), Rgb.parseHex("#FFC46B"))
        assertEquals(Rgb.of(0x0A0B0C), Rgb.parseHex("0a0b0c"))
        assertEquals(null, Rgb.parseHex("oops"))
    }
}
