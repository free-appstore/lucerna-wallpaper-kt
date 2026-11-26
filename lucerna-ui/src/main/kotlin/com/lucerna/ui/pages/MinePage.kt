@file:OptIn(ExperimentalFoundationApi::class)

package com.lucerna.ui.pages

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.render.ColorRenderer
import com.lucerna.ui.components.EmptyHint
import com.lucerna.ui.components.PillButton
import com.lucerna.ui.components.PlayGlyph
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.state.MineFilter
import com.lucerna.ui.theme.LucernaColors
import com.lucerna.ui.theme.LucernaMetrics
import java.io.File

/** 「我的」页：顶部二级分类切换，下方是所选类型的图库。 */
@Composable
fun MinePage(
    state: LucernaState,
) {
    val images = state.items.filter { it.type == WallpaperType.IMAGE }
    val videos = state.items.filter { it.type == WallpaperType.VIDEO }
    val colors = state.savedColors

    if (images.isEmpty() && videos.isEmpty() && colors.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            EmptyHint(
                title = "图库还是空的",
                detail = "到「导入」页挑几张图片或视频",
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        MineFilterBar(
            current = state.mineFilter,
            counts = mapOf(
                MineFilter.IMAGE to images.size,
                MineFilter.VIDEO to videos.size,
                MineFilter.COLOR to colors.size,
            ),
            onSelect = state::selectMineFilter,
        )
        when (state.mineFilter) {
            MineFilter.IMAGE -> if (images.isEmpty()) {
                EmptyHint(
                    title = "还没有图片",
                    detail = "到「导入」页挑几张图片",
                )
            } else {
                TwoColumnGrid(images) { item ->
                    WallpaperCard(
                        item = item,
                        selected = state.settings.currentWallpaperId == item.id && !state.usingColor,
                        onApply = { state.applyItem(item) },
                        onPreview = { state.openPreview(item) },
                        onReveal = { state.openItemLocation(item) },
                        onRemove = { state.removeItem(item) },
                    )
                }
            }

            MineFilter.VIDEO -> if (videos.isEmpty()) {
                EmptyHint(
                    title = "还没有视频",
                    detail = "到「导入」页导入视频",
                )
            } else {
                TwoColumnGrid(videos) { item ->
                    WallpaperCard(
                        item = item,
                        selected = state.settings.currentWallpaperId == item.id && !state.usingColor,
                        onApply = { state.applyItem(item) },
                        onPreview = { state.openPreview(item) },
                        onReveal = { state.openItemLocation(item) },
                        onRemove = { state.removeItem(item) },
                    )
                }
            }

            MineFilter.COLOR -> if (colors.isEmpty()) {
                EmptyHint(
                    title = "还没有色彩壁纸",
                    detail = "在「色彩」页保存后出现在这里",
                )
            } else {
                TwoColumnGrid(colors) { fill ->
                    ColorCard(
                        fill = fill,
                        selected = fill == state.activeColor,
                        state = state,
                    )
                }
            }
        }
    }
}

/**
 * 二级分类切换：三枚小胶囊，靠左排列，尺寸与「导入」页的胶囊一致。
 *
 * 和一级分页条刻意拉开差距：不做整条底框、不做滑动指示器，也没有任何动画；
 * 选中项只是换成琥珀色底和文字。
 */
@Composable
private fun MineFilterBar(
    current: MineFilter,
    counts: Map<MineFilter, Int>,
    onSelect: (MineFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(LucernaMetrics.SecondaryGap),
    ) {
        MineFilter.entries.forEach { filter ->
            PillButton(
                text = filter.label,
                count = counts[filter] ?: 0,
                selected = filter == current,
                onClick = { onSelect(filter) },
            )
        }
    }
}

/** 两列卡片；最后一行只有一张时用占位保持半宽。 */
@Composable
private fun <T> TwoColumnGrid(
    items: List<T>,
    itemContent: @Composable (T) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        items(items.chunked(2)) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    Box(Modifier.weight(1f)) {
                        itemContent(item)
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WallpaperCard(
    item: WallpaperItem,
    selected: Boolean,
    onApply: () -> Unit,
    onPreview: () -> Unit,
    onReveal: () -> Unit,
    onRemove: () -> Unit,
) {
    val menuState = remember { ContextMenuState() }
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("预览") { onPreview() },
                ContextMenuItem("设为壁纸") { onApply() },
                ContextMenuItem("打开文件位置") { onReveal() },
                ContextMenuItem("从图库移除") { onRemove() },
            )
        },
        state = menuState,
    ) {
        CardShell(
            selected = selected,
            onClick = onApply,
        ) {
            if (item.type == WallpaperType.IMAGE) {
                AsyncImage(
                    // 直接给 File：路径里有空格 / 特殊字符时也按本地文件读取
                    model = File(item.thumbnail ?: item.path),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                VideoPlaceholder(item)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xE6000000),
                        ),
                    ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(9.dp),
            ) {
                Text(
                    text = item.name,
                    fontSize = 10.5.sp,
                    color = LucernaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle(),
                    fontSize = 8.5.sp,
                    color = LucernaColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.type == WallpaperType.VIDEO) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayGlyph(Modifier.size(8.dp))
                }
            }
        }
    }
}

/** 视频没有缩略图时用的一张小插画。 */
@Composable
private fun VideoPlaceholder(
    item: WallpaperItem,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2A2233), Color(0xFF121620), Color(0xFF0B0D13)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0x33FFC46B))
                .border(1.dp, Color(0x66FFC46B), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PlayGlyph(
                modifier = Modifier.size(10.dp),
                color = LucernaColors.Accent,
            )
        }
    }
}

/** 「我的」里保存下来的一份色彩壁纸。 */
@Composable
private fun ColorCard(
    fill: ColorFill,
    selected: Boolean,
    state: LucernaState,
) {
    val bitmap = remember(fill) {
        ColorRenderer.render(fill, 360, 240).toComposeImageBitmap()
    }
    val menuState = remember { ContextMenuState() }
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("设为壁纸") { state.applyColor(fill) },
                ContextMenuItem("编辑") { state.editColor(fill) },
                ContextMenuItem("从色彩中移除") { state.removeSavedColor(fill) },
            )
        },
        state = menuState,
    ) {
        CardShell(
            selected = selected,
            onClick = { state.applyColor(fill) },
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "色彩壁纸",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(9.dp)) {
                Text(
                    text = "色彩壁纸",
                    fontSize = 10.5.sp,
                    color = LucernaColors.TextPrimary,
                )
                Text(
                    text = fill.summary(),
                    fontSize = 8.5.sp,
                    color = LucernaColors.TextTertiary,
                )
            }
        }
    }
}

/** 统一卡片外框：圆角、描边、选中高亮。 */
@Composable
private fun CardShell(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.52f)
            .clip(RoundedCornerShape(11.dp))
            .background(LucernaColors.CardStrong)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) LucernaColors.Accent else LucernaColors.Border,
                RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick),
    ) {
        content()
        if (selected) {
            Text(
                text = "使用中",
                fontSize = 8.sp,
                color = Color(0xFF1A1408),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(LucernaColors.Accent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
