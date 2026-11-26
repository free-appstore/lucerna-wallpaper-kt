package com.lucerna.core

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.randomized
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 刷新按钮的随机配色：类型不变，数量 / 角度 / 阶数随机，颜色要“亮”而不脏。 */
class ColorPaletteTest {

    @Test
    fun `随机配色保持类型`() {
        ColorKind.entries.forEach { kind ->
            val next = ColorFill(kind = kind).randomized(Random(42))
            assertEquals(kind, next.kind)
        }
    }

    @Test
    fun `纯色只生成一个颜色`() {
        val next = ColorFill(kind = ColorKind.SOLID).randomized(Random(1))

        assertEquals(1, next.colors.size)
        assertEquals(1, next.wellCount)
    }

    @Test
    fun `渐变随机颜色数量与角度`() {
        val fill = ColorFill(kind = ColorKind.GRADIENT)
        repeat(50) { seed ->
            val next = fill.randomized(Random(seed))
            assertTrue(next.colors.size in 2..4, "颜色数量越界: ${next.colors.size}")
            assertTrue(next.angle in ColorFill.DIRECTIONS, "角度不是 8 个方向之一: ${next.angle}")
            next.colors.forEach(::assertLivelyColor)
        }
    }

    @Test
    fun `色阶随机颜色数量角度与阶数`() {
        val fill = ColorFill(kind = ColorKind.STEPS)
        repeat(50) { seed ->
            val next = fill.randomized(Random(seed))
            assertTrue(next.colors.size in 2..5, "颜色数量越界: ${next.colors.size}")
            assertTrue(next.steps in next.colors.size..12, "阶数越界: ${next.steps}")
            assertTrue(next.angle in ColorFill.DIRECTIONS, "角度不是 8 个方向之一: ${next.angle}")
            next.colors.forEach(::assertLivelyColor)
        }
    }

    @Test
    fun `随机方向只使用八个预设角度`() {
        val directions = (0 until 200)
            .map { ColorFill(kind = ColorKind.GRADIENT).randomized(Random(it)).angle }
            .toSet()

        assertEquals(ColorFill.DIRECTIONS.toSet(), directions)
    }

    @Test
    fun `同一个种子生成相同配色`() {
        val fill = ColorFill(kind = ColorKind.GRADIENT)

        assertEquals(fill.randomized(Random(7)), fill.randomized(Random(7)))
    }

    @Test
    fun `不同种子会得到不同配色`() {
        val fill = ColorFill(kind = ColorKind.GRADIENT)
        val generated = (0 until 20).map { fill.randomized(Random(it)) }.toSet()

        assertTrue(generated.size > 1)
    }

    /** 不是灰阶，也不是接近纯黑 / 纯白的颜色。 */
    private fun assertLivelyColor(color: com.lucerna.core.model.Rgb) {
        val max = maxOf(color.r, color.g, color.b)
        val min = minOf(color.r, color.g, color.b)
        assertTrue(max - min > 0.05f, "颜色太灰: $color")
        assertTrue(max > 0.25f, "颜色太暗: $color")
        assertTrue(min < 0.95f, "颜色太亮: $color")
    }
}
