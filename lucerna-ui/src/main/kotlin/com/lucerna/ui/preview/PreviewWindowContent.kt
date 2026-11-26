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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
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
import java.awt.MouseInfo
import java.awt.Point
import java.io.File
import javax.swing.JFrame
import kotlin.math.roundToInt

/** 预览窗口标题栏高度。 */
private val PreviewHeaderHeight = 46.dp

/** 预览窗口底栏高度。 */
private val PreviewFooterHeight = 34.dp

/**
 * 预览窗口里标题栏与底栏的总高度（dp）。
 *
 * 应用层用屏幕高度减去它来算中间的画面区域，保证画面区域正好是媒体宽高比；
 * 调整上面两个高度时要同步改这里。
 */
const val PREVIEW_CHROME_HEIGHT = 80

/**
 * 预览窗口的正文：图片走 Coil3，视频走 MediaMP。
 *
 * 支持 ⌘+滚轮 / 触控板双指捏合缩放 1–8 倍、按住拖拽平移；视频按原始比例完整显示。
 */
@Composable
fun WindowScope.PreviewWindowContent(
    item: WallpaperItem,
    volume: Float,
    muted: Boolean,
    onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    onClose: () -> Unit,
) {
    val hostWindow = window
    // 竖屏媒体的窗口会很窄：收起次要信息，避免标题栏 / 底栏互相挤压
    val compact = LocalWindowInfo.current.containerDpSize.width < 360.dp
    // 用 State 对象而不是解构后的值：捏合回调只创建一次，必须能读到最新的缩放 / 平移
    val zoomState = remember(item.id) { mutableStateOf(1f) }
    val panState = remember(item.id) { mutableStateOf(Offset.Zero) }
    val viewportState = remember { mutableStateOf(IntSize.Zero) }
    // 指针在预览区内的最后位置；捏合手势本身没有坐标，用它当缩放中心
    val pointerState = remember { mutableStateOf<Offset?>(null) }
    var zoom by zoomState
    var pan by panState

    /**
     * 把缩放调到 [next]，并保持 [anchor] 处的内容不动。
     *
     * [anchor] 为空时以预览区中心为锚点（头部 +/- 按钮）；滚轮与捏合都会传入指针位置。
     */
    fun setZoom(next: Float, anchor: Offset? = null) {
        val currentZoom = zoomState.value
        val target = next.coerceIn(1f, 8f)
        if (target == currentZoom) return
        val size = viewportState.value
        if (target <= 1f) {
            // 原尺寸时画面正好铺满，不需要平移
            panState.value = Offset.Zero
        } else {
            panState.value = anchoredPan(
                pan = panState.value,
                currentZoom = currentZoom,
                targetZoom = target,
                viewportSize = size,
                focus = anchor ?: Offset(size.width / 2f, size.height / 2f),
            )
        }
        zoomState.value = target
    }

    // 触控板双指捏合：JDK 的 eAWT 手势，挂到窗口的 rootPane 上
    DisposableEffect(hostWindow) {
        val rootPane = (hostWindow as? JFrame)?.rootPane
        val pinch = rootPane?.let { pane ->
            installPinchZoom(pane) { magnification ->
                setZoom(zoomState.value * (1f + magnification), pointerState.value)
            }
        }
        onDispose { pinch?.close() }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF20E1016))
            .border(1.dp, LucernaColors.BorderStrong, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PreviewHeaderHeight)
                .background(Color(0x0DFFFFFF))
                .pointerInput(hostWindow) {
                    // 拖拽时窗口会跟着指针移动，Compose 的 drag 增量是窗口内坐标，
                    // 窗口一动它就跟着变，再用增量移动窗口会形成反馈 → 瞬移。
                    // 所以用 MouseInfo 的屏幕坐标：记录按下时的指针和窗口位置，
                    // 之后只按「指针相对按下点移动了多少」设置窗口位置。
                    var pointerAtStart: Point? = null
                    var windowAtStart: Point? = null
                    detectDragGestures(
                        onDragStart = {
                            pointerAtStart = MouseInfo.getPointerInfo()?.location
                            windowAtStart = hostWindow.location
                        },
                        onDragEnd = {
                            pointerAtStart = null
                            windowAtStart = null
                        },
                        onDragCancel = {
                            pointerAtStart = null
                            windowAtStart = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val pointer = MouseInfo.getPointerInfo()?.location
                            val startPointer = pointerAtStart
                            val startWindow = windowAtStart
                            if (pointer != null && startPointer != null && startWindow != null) {
                                hostWindow.location = Point(
                                    startWindow.x + pointer.x - startPointer.x,
                                    startWindow.y + pointer.y - startPointer.y,
                                )
                            }
                        },
                    )
                }
                .padding(horizontal = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Text(
                    text = item.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = LucernaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!compact) {
                    Text(
                        text = item.subtitle(),
                        fontSize = 9.5.sp,
                        color = LucernaColors.TextTertiary,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                ZoomButton { setZoom(zoom - 0.5f) }
                if (!compact) {
                    Text(
                        text = "${(zoom * 100).roundToInt()}%",
                        fontSize = 10.sp,
                        color = LucernaColors.TextSecondary,
                        modifier = Modifier.width(46.dp).padding(horizontal = 4.dp),
                    )
                }
                ZoomButton(positive = true) { setZoom(zoom + 0.5f) }
                Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
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
                .clipToBounds()
                .onSizeChanged { viewportState.value = it }
                .onPointerEvent(PointerEventType.Move) { event ->
                    pointerState.value = event.changes.firstOrNull()?.position
                }
                .onPointerEvent(PointerEventType.Exit) { pointerState.value = null }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val delta = change.scrollDelta.y
                    if (delta != 0f) {
                        pointerState.value = change.position
                        setZoom(zoomState.value * (1f - delta * 0.1f), change.position)
                    }
                }
                .pointerInput(zoom) {
                    if (zoom <= 1.001f) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        panState.value = clampPan(
                            pan = panState.value + drag,
                            viewportSize = viewportState.value,
                            zoom = zoomState.value,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            ScaledViewport(
                zoom = zoom,
                pan = pan,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!item.exists) {
                    Text(
                        text = "文件已被移动或删除",
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
                        onVideoSize = onVideoSize,
                    )
                } else {
                    AsyncImage(
                        // 直接给 File：不经过 file: URI，路径里的空格 / 特殊字符不会被二次编码
                        model = File(item.path),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PreviewFooterHeight)
                .background(Color(0x0AFFFFFF))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⌘ 滚轮 / 双指捏合缩放 · 拖拽平移 · 1×–8×",
                fontSize = 9.5.sp,
                color = LucernaColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (item.type == WallpaperType.VIDEO) "MediaMP" else "Coil3",
                    fontSize = 9.5.sp,
                    color = LucernaColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun ZoomButton(
    positive: Boolean = false,
    onClick: () -> Unit,
) {
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

/**
 * 以 [focus]（预览区坐标，像素）为中心缩放到 [targetZoom]，返回新的平移量。
 *
 * 画面按 [zoom] 缩放后居中绘制、再平移 [pan]：预览区坐标 p 对应的内容坐标为
 * `(p - 中心 - pan) / zoom + 中心`。要让 [focus] 下的内容在缩放后仍停在 [focus]，
 * 新平移量必须满足 `factor * pan + (1 - factor) * (focus - 中心)`（factor = 新 / 旧缩放）。
 */
internal fun anchoredPan(
    pan: Offset,
    currentZoom: Float,
    targetZoom: Float,
    viewportSize: IntSize,
    focus: Offset,
): Offset {
    val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
    val factor = targetZoom / currentZoom
    val shifted = Offset(
        x = factor * pan.x + (1f - factor) * (focus.x - center.x),
        y = factor * pan.y + (1f - factor) * (focus.y - center.y),
    )
    return clampPan(shifted, viewportSize, targetZoom)
}

/** 平移不能超过缩放后多出来的那部分，避免画面边缘露出空白。 */
internal fun clampPan(
    pan: Offset,
    viewportSize: IntSize,
    zoom: Float,
): Offset {
    val maxX = (viewportSize.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (viewportSize.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
    return Offset(
        // 原始大小时没有可平移空间，直接归零（避免得到 -0.0）
        x = if (maxX > 0f) pan.x.coerceIn(-maxX, maxX) else 0f,
        y = if (maxY > 0f) pan.y.coerceIn(-maxY, maxY) else 0f,
    )
}
