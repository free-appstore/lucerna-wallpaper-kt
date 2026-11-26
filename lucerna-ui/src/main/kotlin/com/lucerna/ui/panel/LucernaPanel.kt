package com.lucerna.ui.panel

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucerna.core.util.MemoryStats
import com.lucerna.ui.components.FireflyLogo
import com.lucerna.ui.components.PowerGlyph
import com.lucerna.ui.pages.ColorPage
import com.lucerna.ui.pages.ImportPage
import com.lucerna.ui.pages.MinePage
import com.lucerna.ui.pages.SettingsPage
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.state.PanelTab
import com.lucerna.ui.theme.LucernaColors
import com.lucerna.ui.theme.LucernaText
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * 状态栏弹出的主面板。
 *
 * 尺寸固定为 392×736（正好装下「色彩」页），四周留出 1px 半透明描边和一圈阴影；
 * 分页内容自行排布，面板本身不滚动。
 */
@Composable
fun LucernaPanel(state: LucernaState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .shadow(28.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(LucernaColors.panelBrush)
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(
                memoryMb = rememberMemoryMegabytes(),
                onClose = { state.actions.quit() },
            )
            TabBar(current = state.tab, onSelect = state::selectTab)
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                when (state.tab) {
                    PanelTab.MINE -> MinePage(state)
                    PanelTab.COLOR -> ColorPage(state)
                    PanelTab.IMPORT -> ImportPage(state)
                    PanelTab.SETTINGS -> SettingsPage(state)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        ToastOverlay(state)
    }
}

@Composable
private fun PanelHeader(memoryMb: Long, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(66.dp).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FireflyLogo(Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Lucerna", style = LucernaText.title)
            Text(
                "灵萤 · 桌面壁纸 · 内存 $memoryMb MB",
                style = LucernaText.subtitle,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x1FFF7A7A))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            PowerGlyph(Modifier.size(15.dp))
        }
    }
}

@Composable
private fun TabBar(current: PanelTab, onSelect: (PanelTab) -> Unit) {
    val tabs = PanelTab.entries
    val spacing = 3.dp
    // 选中状态的滑块在分页之间平移，而不是直接跳过去
    val progress by animateFloatAsState(
        targetValue = tabs.indexOf(current).coerceAtLeast(0).toFloat(),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tabIndicator",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0CFFFFFF))
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val itemWidth = (maxWidth - spacing * (tabs.size - 1)) / tabs.size
            Box(
                modifier = Modifier
                    .offset(x = (itemWidth + spacing) * progress)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(LucernaColors.AccentSoft),
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                tabs.forEachIndexed { index, tab ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { onSelect(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 文字颜色跟着滑块走：滑块经过时两边的标签一起过渡
                        val distance = abs(progress - index).coerceIn(0f, 1f)
                        Text(
                            tab.label,
                            fontSize = 12.sp,
                            fontWeight = if (tab == current) FontWeight.SemiBold else FontWeight.Normal,
                            color = lerp(LucernaColors.TextSecondary, LucernaColors.Accent, 1f - distance),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastOverlay(state: LucernaState) {
    val message = state.toast
    LaunchedEffect(message) {
        if (message != null) {
            delay(2.2.seconds)
            state.consumeToast()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.padding(bottom = 22.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF21A1D27))
                    .border(1.dp, LucernaColors.BorderStrong, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(message.orEmpty(), fontSize = 11.sp, color = LucernaColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun rememberMemoryMegabytes(): Long {
    var memory by remember { mutableStateOf(MemoryStats.usedMegabytes()) }
    LaunchedEffect(Unit) {
        while (true) {
            memory = MemoryStats.usedMegabytes()
            delay(2.seconds)
        }
    }
    return memory
}
