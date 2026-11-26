package com.lucerna.app

import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals

/** 预览窗口尺寸的纯计算：高度固定为屏幕一半，画面区域按媒体宽高比自适应。 */
class PreviewGeometryTest {

    @Test
    fun `横屏图片用屏幕高度一半和媒体比例算窗口`() {
        val size = PanelPlacement.previewWindowSize(2560, 1440, 3840, 2160)

        assertEquals(Dimension(1138, 720), size)
    }

    @Test
    fun `竖屏图片的宽度按比例收窄`() {
        val size = PanelPlacement.previewWindowSize(1440, 900, 1080, 1920)

        // 窗口高 450；画面区高 450 - 80 = 370，宽 = 370 × 9/16
        assertEquals(450, size.height)
        assertEquals(208, size.width)
    }

    @Test
    fun `超宽图片受屏幕宽度限制而高度按比例收回`() {
        val size = PanelPlacement.previewWindowSize(1440, 900, 8000, 1000)

        assertEquals(1424, size.width)
        val contentRatio = size.width.toFloat() / (size.height - 80)
        assertEquals(8f, contentRatio, 0.02f)
    }

    @Test
    fun `尺寸未知时按16比9占位`() {
        assertEquals(
            PanelPlacement.previewWindowSize(1440, 900, 1920, 1080),
            PanelPlacement.previewWindowSize(1440, 900, 0, 0),
        )
    }
}
