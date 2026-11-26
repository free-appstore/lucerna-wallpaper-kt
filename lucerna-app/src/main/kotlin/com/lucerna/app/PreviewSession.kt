package com.lucerna.app

import androidx.compose.ui.graphics.TransformOrigin
import com.lucerna.core.model.WallpaperItem
import java.awt.Point
import java.awt.Rectangle

/**
 * 一次预览：最终窗口位置，以及触发预览时鼠标的屏幕坐标。
 *
 * 窗口始终待在最终的居中位置；内容以光标为原点做 0→1 的缩放和透明度动画，
 * 视觉上就是从点「预览」的位置展开到屏幕中央。
 */
internal data class PreviewSession(
    val item: WallpaperItem,
    val bounds: Rectangle,
    val anchor: Point?,
    /** 打开前是否已经读到真实媒体尺寸；视频探测失败时留 false，等播放器回报后再校正窗口。 */
    val mediaSizeKnown: Boolean = true,
) {
    /**
     * 动画缩放中心：光标相对最终窗口的位置。
     *
     * 光标可能落在窗口外（例如面板顶部弹出的菜单），分量允许小于 0 或大于 1；
     * graphicsLayer 的原点可以落在窗口外，缩放方向仍然是从光标展开到窗口中心。
     */
    fun transformOrigin(): TransformOrigin {
        val cursor = anchor ?: return TransformOrigin.Center
        if (bounds.width <= 0 || bounds.height <= 0) return TransformOrigin.Center
        return TransformOrigin(
            pivotFractionX = (cursor.x - bounds.x).toFloat() / bounds.width,
            pivotFractionY = (cursor.y - bounds.y).toFloat() / bounds.height,
        )
    }
}
