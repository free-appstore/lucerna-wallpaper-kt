package com.lucerna.app

import androidx.compose.ui.graphics.TransformOrigin
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

/** 预览窗口展开 / 收回动画的缩放原点。 */
class PreviewSessionTest {

    private val item = WallpaperItem(
        id = "test",
        name = "test",
        type = WallpaperType.IMAGE,
        path = "/nonexistent/test",
    )

    @Test
    fun `光标在窗口内时按相对位置计算原点`() {
        val session = PreviewSession(
            item = item,
            bounds = Rectangle(100, 200, 400, 300),
            anchor = Point(300, 350),
        )

        val origin = session.transformOrigin()

        assertEquals(0.5f, origin.pivotFractionX, 0.001f)
        assertEquals(0.5f, origin.pivotFractionY, 0.001f)
    }

    @Test
    fun `光标在窗口外时原点允许超出0到1`() {
        val session = PreviewSession(
            item = item,
            bounds = Rectangle(100, 200, 400, 300),
            anchor = Point(0, 0),
        )

        val origin = session.transformOrigin()

        assertEquals(-0.25f, origin.pivotFractionX, 0.001f)
        assertEquals(-0.6667f, origin.pivotFractionY, 0.001f)
    }

    @Test
    fun `没有光标位置时从窗口中心缩放`() {
        val session = PreviewSession(
            item = item,
            bounds = Rectangle(0, 0, 200, 100),
            anchor = null,
        )

        assertEquals(TransformOrigin.Center, session.transformOrigin())
    }
}
