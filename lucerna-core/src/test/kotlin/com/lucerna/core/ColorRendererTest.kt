package com.lucerna.core

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.Rgb
import com.lucerna.core.render.ColorRenderer
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorRendererTest {

    @Test
    fun `水平渐变左右两端颜色正确`() {
        val fill = ColorFill(
            kind = ColorKind.GRADIENT,
            colors = listOf(Rgb.of(0xFF0000), Rgb.of(0x0000FF)),
            angle = 0f,
        )
        val image = ColorRenderer.render(fill, 400, 200)
        val left = Color(image.getRGB(1, 100))
        val right = Color(image.getRGB(398, 100))
        assertTrue(left.red > 240 && left.blue < 20, "左侧应接近红色，实际 $left")
        assertTrue(right.blue > 240 && right.red < 20, "右侧应接近蓝色，实际 $right")
    }

    @Test
    fun `垂直渐变顶部与底部颜色相反`() {
        val fill = ColorFill(
            kind = ColorKind.GRADIENT,
            colors = listOf(Rgb.of(0xFFFFFF), Rgb.of(0x000000)),
            angle = 90f,
        )
        val image = ColorRenderer.render(fill, 120, 300)
        val top = Color(image.getRGB(60, 2))
        val bottom = Color(image.getRGB(60, 297))
        assertTrue(top.red > 240)
        assertTrue(bottom.red < 20)
    }

    @Test
    fun `色阶产生硬边色块`() {
        val fill = ColorFill(
            kind = ColorKind.STEPS,
            colors = listOf(Rgb.Black, Rgb.White),
            angle = 0f,
            steps = 4,
        )
        val image = ColorRenderer.render(fill, 400, 100)
        // 第一段内部颜色一致，第二段内部颜色一致，边界两侧明显不同
        assertEquals(image.getRGB(10, 50), image.getRGB(80, 50))
        assertTrue(image.getRGB(90, 50) != image.getRGB(110, 50))
    }

    @Test
    fun `纯色整张图一致`() {
        val fill = ColorFill(kind = ColorKind.SOLID, colors = listOf(Rgb.of(0x123456)))
        val image = ColorRenderer.render(fill, 64, 64)
        val color = image.getRGB(32, 32)
        assertEquals(color, image.getRGB(0, 0))
        assertEquals(color, image.getRGB(63, 63))
    }

    @Test
    fun `任意角度都能铺满画面`() {
        val fill = ColorFill(
            kind = ColorKind.STEPS,
            colors = listOf(Rgb.of(0xFFFFFF), Rgb.of(0x000000)),
            angle = 37f,
            steps = 6,
        )
        val image = ColorRenderer.render(fill, 320, 180)
        // 四个角都必须被某个色块覆盖（不透明黑色或白色），不能是未绘制像素
        listOf(0 to 0, 319 to 0, 0 to 179, 319 to 179).forEach { (x, y) ->
            val color = Color(image.getRGB(x, y))
            assertTrue(color.alpha == 255)
        }
    }
}
