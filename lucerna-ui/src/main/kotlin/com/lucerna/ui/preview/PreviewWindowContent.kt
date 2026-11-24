@file:OptIn(ExperimentalComposeUiApi::class)

package com.lucerna.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import coil3.compose.AsyncImage
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.media.VideoSurface
import com.lucerna.ui.components.CloseGlyph
import com.lucerna.ui.components.MinusGlyph
import com.lucerna.ui.components.PlusGlyph
import com.lucerna.ui.theme.LucernaColors
import java.awt.Point
import java.io.File
import kotlin.math.roundToInt

/**
 * 预览窗口的正文：图片走 Coil3，视频走 MediaMP。
 *
 * 支持滚轮缩放 1–8 倍、按住拖拽平移；视频按原始比例完整显示。
 */
@Composable
fun WindowScope.PreviewWindowContent(
    item: WallpaperItem,
    volume: Float,
    muted: Boolean,
    onClose: () -> Unit,
) {
    val hostWindow = window
    var zoom by remember(item.id) { mutableStateOf(1f) }
    var pan by remember(item.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun setZoom(next: Float) {
        zoom = next.coerceIn(1f, 8f)
        if (zoom <= 1.001f) pan = Offset.Zero
    }

    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF20E1016))
            .border(1.dp, LucernaColors.BorderStrong, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(Color(0x0DFFFFFF))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        hostWindow.location = Point(
                            hostWindow.x + drag.x.roundToInt(),
                            hostWindow.y + drag.y.roundToInt(),
                        )
                    }
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Text(
                    item.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = LucernaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    item.subtitle(),
                    fontSize = 9.5.sp,
                    color = LucernaColors.TextTertiary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                ZoomButton { setZoom(zoom - 0.5f) }
                Text(
                    "${(zoom * 100).roundToInt()}%",
                    fontSize = 10.sp,
                    color = LucernaColors.TextSecondary,
                    modifier = Modifier.width(46.dp).padding(horizontal = 4.dp),
                )
                ZoomButton(positive = true) { setZoom(zoom + 0.5f) }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0x14FFFFFF))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    CloseGlyph(Modifier.size(11.dp))
                }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { viewport = it }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (delta != 0f) {
                        setZoom(zoom * (1f - delta * 0.1f))
                    }
                }
                .pointerInput(zoom) {
                    if (zoom <= 1.001f) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        val maxX = viewport.width * (zoom - 1f) / 2f
                        val maxY = viewport.height * (zoom - 1f) / 2f
                        pan = Offset(
                            (pan.x + drag.x).coerceIn(-maxX, maxX),
                            (pan.y + drag.y).coerceIn(-maxY, maxY),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ScaledViewport(zoom = zoom, pan = pan, modifier = Modifier.fillMaxSize()) {
                    if (!item.exists) {
                        Text(
                            "文件已被移动或删除",
                            fontSize = 12.sp,
                            color = LucernaColors.Danger,
                            modifier = Modifier.fillMaxSize().wrapContentSize(),
                        )
                    } else if (item.type == WallpaperType.VIDEO) {
                        VideoSurface(
                            path = item.path,
                            modifier = Modifier.fillMaxSize(),
                            volume = volume,
                            muted = muted,
                            loop = true,
                        )
                    } else {
                        AsyncImage(
                            model = File(item.path).toURI().toString(),
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Color(0x0AFFFFFF))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "滚轮缩放 · 拖拽平移 · 1×–8×",
                fontSize = 9.5.sp,
                color = LucernaColors.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (item.type == WallpaperType.VIDEO) "MediaMP" else "Coil3",
                fontSize = 9.5.sp,
                color = LucernaColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun ZoomButton(positive: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x14FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (positive) PlusGlyph(Modifier.size(9.dp))
        else MinusGlyph(Modifier.size(9.dp))
    }
}

/**
 * 按 zoom 放大子内容并平移。
 *
 * 用布局尺寸缩放而不是 graphicsLayer，原生视频表面（MediaMP）也能跟着变大。
 */
@Composable
private fun ScaledViewport(
    zoom: Float,
    pan: Offset,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val childWidth = (width * zoom).roundToInt().coerceAtLeast(1)
        val childHeight = (height * zoom).roundToInt().coerceAtLeast(1)
        val placeable = measurables.firstOrNull()?.measure(Constraints.fixed(childWidth, childHeight))
        layout(width, height) {
            if (placeable != null) {
                val dx = ((width - childWidth) / 2f + pan.x).roundToInt()
                val dy = ((height - childHeight) / 2f + pan.y).roundToInt()
                placeable.place(dx, dy)
            }
        }
    }
}
