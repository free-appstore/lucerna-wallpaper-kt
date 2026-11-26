package com.lucerna.ui.preview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomAnchorTest {

    private val viewport = IntSize(width = 800, height = 600)
    private val center = Offset(400f, 300f)

    @Test
    fun `放大时指针下的内容保持不动`() {
        val pan = Offset(40f, -20f)
        val currentZoom = 2f
        val targetZoom = 3f
        val focus = Offset(620f, 150f)

        val next = anchoredPan(pan, currentZoom, targetZoom, viewport, focus)

        assertEquals(focus.x, screenX(contentXAt(focus.x, currentZoom, pan.x), targetZoom, next.x), 0.01f)
        assertEquals(focus.y, screenY(contentYAt(focus.y, currentZoom, pan.y), targetZoom, next.y), 0.01f)
    }

    @Test
    fun `缩小时指针下的内容保持不动`() {
        val pan = Offset(-50f, 45f)
        val currentZoom = 4f
        val targetZoom = 2.5f
        val focus = Offset(180f, 480f)

        val next = anchoredPan(pan, currentZoom, targetZoom, viewport, focus)

        assertEquals(focus.x, screenX(contentXAt(focus.x, currentZoom, pan.x), targetZoom, next.x), 0.01f)
        assertEquals(focus.y, screenY(contentYAt(focus.y, currentZoom, pan.y), targetZoom, next.y), 0.01f)
    }

    @Test
    fun `回到原始大小时平移归零`() {
        val next = anchoredPan(
            pan = Offset(120f, -80f),
            currentZoom = 4f,
            targetZoom = 1f,
            viewportSize = viewport,
            focus = Offset(700f, 100f),
        )

        assertEquals(Offset.Zero, next)
    }

    @Test
    fun `平移被限制在缩放后多出来的范围内`() {
        val clamped = clampPan(Offset(1000f, -900f), viewport, zoom = 2f)

        assertEquals(400f, clamped.x)
        assertEquals(-300f, clamped.y)
    }

    /** 预览区横坐标 -> 内容横坐标（缩放前）。 */
    private fun contentXAt(screen: Float, zoom: Float, pan: Float): Float =
        (screen - center.x - pan) / zoom + center.x

    private fun contentYAt(screen: Float, zoom: Float, pan: Float): Float =
        (screen - center.y - pan) / zoom + center.y

    /** 内容横坐标 -> 预览区横坐标（缩放后）。 */
    private fun screenX(content: Float, zoom: Float, pan: Float): Float =
        center.x + pan + zoom * (content - center.x)

    private fun screenY(content: Float, zoom: Float, pan: Float): Float =
        center.y + pan + zoom * (content - center.y)
}
