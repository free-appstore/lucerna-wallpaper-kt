package com.lucerna.ui.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay

/**
 * 状态栏弹出的主面板。
 *
 * 尺寸固定为 392×640，四周留出 1px 半透明描边和一圈阴影；
 * 分页内容自行滚动，面板本身不滚动。
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0CFFFFFF))
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PanelTab.entries.forEach { tab ->
            val selected = tab == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) LucernaColors.AccentSoft else Color.Transparent)
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) LucernaColors.Accent else LucernaColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ToastOverlay(state: LucernaState) {
    val message = state.toast
    LaunchedEffect(message) {
        if (message != null) {
            delay(2200)
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
            delay(2000)
        }
    }
    return memory
}
