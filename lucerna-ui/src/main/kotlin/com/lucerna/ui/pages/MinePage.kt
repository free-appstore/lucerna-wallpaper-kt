@file:OptIn(ExperimentalFoundationApi::class)

package com.lucerna.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.render.ColorRenderer
import com.lucerna.ui.components.ChevronGlyph
import com.lucerna.ui.components.EmptyHint
import com.lucerna.ui.components.PlayGlyph
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.state.PanelTab
import com.lucerna.ui.theme.LucernaColors
import java.io.File

/** 「我的」页的二级分类。 */
private enum class MineSection(val key: String, val label: String) {
    PHOTOS("photos", "照片"),
    VIDEOS("videos", "视频"),
    COLOR("color", "色彩"),
}

/** 「我的」页：照片 / 视频 / 色彩 三个可折叠分组。 */
@Composable
fun MinePage(state: LucernaState) {
    val photos = state.items.filter { it.type == WallpaperType.IMAGE }
    val videos = state.items.filter { it.type == WallpaperType.VIDEO }
    val hasColor = state.usingColor

    if (photos.isEmpty() && videos.isEmpty() && !hasColor) {
        Column(Modifier.fillMaxSize()) {
            EmptyHint("图库还是空的", "到「导入」页挑几张图片或视频")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        if (photos.isNotEmpty()) {
            item(key = MineSection.PHOTOS.key) {
                CollapsibleSection(MineSection.PHOTOS, photos.size, state) {
                    PhotoGrid(photos, state)
                }
            }
        }
        if (videos.isNotEmpty()) {
            item(key = MineSection.VIDEOS.key) {
                CollapsibleSection(MineSection.VIDEOS, videos.size, state) {
                    PhotoGrid(videos, state)
                }
            }
        }
        if (hasColor) {
            item(key = MineSection.COLOR.key) {
                CollapsibleSection(MineSection.COLOR, 1, state) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { ColorEntryCard(state) }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    section: MineSection,
    count: Int,
    state: LucernaState,
    content: @Composable () -> Unit,
) {
    val expanded = state.isSectionExpanded(section.key)
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            section = section,
            count = count,
            expanded = expanded,
            onToggle = { state.toggleSection(section.key) },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(200), expandFrom = Alignment.Top) +
                fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.Top) +
                fadeOut(animationSpec = tween(120)),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                content()
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SectionHeader(
    section: MineSection,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "section-chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChevronGlyph(Modifier.size(10.dp).rotate(rotation))
        Spacer(Modifier.width(7.dp))
        Text(
            "${section.label}（$count）",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = LucernaColors.TextSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0x12FFFFFF)),
        )
    }
}

/** 两列卡片；最后一行只有一张时用占位保持半宽。 */
@Composable
private fun PhotoGrid(items: List<WallpaperItem>, state: LucernaState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    Box(Modifier.weight(1f)) {
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
                    model = File(item.thumbnail ?: item.path).toURI().toString(),
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
                    item.name,
                    fontSize = 10.5.sp,
                    color = LucernaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.subtitle(),
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
private fun VideoPlaceholder(item: WallpaperItem) {
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
            PlayGlyph(Modifier.size(10.dp), color = LucernaColors.Accent)
        }
    }
}

/** 当前正在使用的色彩壁纸入口。 */
@Composable
private fun ColorEntryCard(state: LucernaState) {
    val fill = state.settings.colorFill
    val bitmap = remember(fill) {
        ColorRenderer.render(fill, 360, 240).toComposeImageBitmap()
    }
    val menuState = remember { ContextMenuState() }
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("编辑") { state.selectTab(PanelTab.COLOR) },
                ContextMenuItem("打开文件位置") { state.openRenders() },
            )
        },
        state = menuState,
    ) {
        CardShell(selected = true, onClick = { state.selectTab(PanelTab.COLOR) }) {
            Image(bitmap, contentDescription = "色彩壁纸", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(9.dp)) {
                Text("色彩壁纸", fontSize = 10.5.sp, color = LucernaColors.TextPrimary)
                Text(fill.summary(), fontSize = 8.5.sp, color = LucernaColors.TextTertiary)
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
                "使用中",
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
