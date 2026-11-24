package com.lucerna.ui.pages

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.util.MemoryStats
import com.lucerna.ui.components.GlassCard
import com.lucerna.ui.components.GhostButton
import com.lucerna.ui.components.ThinDivider
import com.lucerna.ui.components.ToggleRow
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.theme.LucernaColors
import kotlin.math.roundToInt

/** 「设置」页：声音、禁用、登录启动、屏保与存储位置。 */
@Composable
fun SettingsPage(state: LucernaState) {
    val settings = state.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionLabel("外观")
        GlassCard(Modifier.fillMaxWidth()) {
            Text("菜单栏图标", fontSize = 12.sp, color = LucernaColors.TextPrimary)
            Text(
                "彩色是萤火虫本来的配色，单色是纯白剪影；左右滑动切换",
                fontSize = 10.sp,
                color = LucernaColors.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            IconStyleSlider(
                selected = settings.trayIconStyle,
                onSelect = state::setTrayIconStyle,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("播放")
        GlassCard(Modifier.fillMaxWidth()) {
            ToggleRow(
                title = "视频声音",
                subtitle = "关闭后视频壁纸保持静音",
                checked = settings.audioEnabled,
                onCheckedChange = state::setAudioEnabled,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
                Slider(
                    value = settings.volume,
                    onValueChange = state::setVolume,
                    valueRange = 0f..1f,
                    enabled = settings.audioEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = if (settings.audioEnabled) LucernaColors.Accent else LucernaColors.TextTertiary,
                        activeTrackColor = if (settings.audioEnabled) LucernaColors.Accent else LucernaColors.Track,
                        inactiveTrackColor = LucernaColors.Track,
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(
                    "${(settings.volume * 100).roundToInt()}%",
                    fontSize = 10.sp,
                    color = if (settings.audioEnabled) LucernaColors.TextSecondary else LucernaColors.TextTertiary,
                )
            }
            ThinDivider(Modifier.padding(vertical = 4.dp))
            ToggleRow(
                title = "禁用壁纸",
                subtitle = "暂停动态壁纸并隐藏视频窗口，省电或临时让出性能",
                checked = settings.disabled,
                onCheckedChange = state::setDisabled,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("启动与屏保")
        GlassCard(Modifier.fillMaxWidth()) {
            ToggleRow(
                title = "登录时启动",
                subtitle = "开机后在状态栏静默运行，不显示 Dock 图标",
                checked = settings.launchAtLogin,
                onCheckedChange = state::setLaunchAtLogin,
            )
            ThinDivider(Modifier.padding(vertical = 4.dp))
            ToggleRow(
                title = "屏幕保护程序",
                subtitle = "进入屏保时保留当前壁纸",
                checked = settings.screensaverWallpaper,
                onCheckedChange = state::setScreensaverWallpaper,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "本机屏保设置为 1 分钟且无需密码",
                    fontSize = 9.5.sp,
                    color = LucernaColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                GhostButton("立即测试", onClick = state::testScreensaver)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("多屏与空间")
        GlassCard(Modifier.fillMaxWidth()) {
            ToggleRow(
                title = "在所有空间中显示",
                subtitle = "需先在系统设置 → 墙纸中打开对应开关，之后切换壁纸会同步全部空间",
                checked = settings.allSpacesShared,
                onCheckedChange = state::setAllSpacesShared,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "全屏 app 覆盖的屏幕也能立即更新，无需重启壁纸服务",
                    fontSize = 9.5.sp,
                    color = LucernaColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                GhostButton("打开系统墙纸设置", onClick = state::openWallpaperSettings)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("存储")
        GlassCard(Modifier.fillMaxWidth()) {
            StorageRow(
                title = "图库文件",
                path = state.paths.mediaDir.toFile().absolutePath,
                onOpen = state::openMediaLibrary,
            )
            Spacer(Modifier.height(6.dp))
            StorageRow(
                title = "色彩渲染",
                path = state.paths.renderDir.toFile().absolutePath,
                onOpen = state::openRenders,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x08FFFFFF))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Lucerna 1.0.0", fontSize = 10.5.sp, color = LucernaColors.TextSecondary)
                Text("灵萤 · 桌面壁纸", fontSize = 9.5.sp, color = LucernaColors.TextTertiary)
            }
            Text(
                "内存 ${MemoryStats.usedMegabytes()} MB",
                fontSize = 9.5.sp,
                color = LucernaColors.TextTertiary,
            )
        }
    }
}

/**
 * 两段的水平滑动开关：点一下选中，或者左右拖拽切换。
 *
 * 轨道上有一个跟着选项滑动的胶囊，两个选项文字压在胶囊上面。
 */
@Composable
private fun IconStyleSlider(
    selected: TrayIconStyle,
    onSelect: (TrayIconStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(TrayIconStyle.COLOR, TrayIconStyle.MONO)
    val index = options.indexOf(selected).coerceAtLeast(0)
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x12FFFFFF))
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(10.dp)),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val segment = maxWidth / options.size
        val thumbOffset by animateDpAsState(segment * index, label = "trayIconStyle")

        fun pick(x: Float) {
            val ratio = (x / widthPx).coerceIn(0f, 0.999f)
            onSelect(options[(ratio * options.size).toInt()])
        }

        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segment)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LucernaColors.AccentSoft),
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx) { detectTapGestures { pick(it.x) } }
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures { change, _ ->
                        pick(change.position.x)
                        change.consume()
                    }
                },
        ) {
            Row(Modifier.fillMaxSize()) {
                options.forEachIndexed { optionIndex, option ->
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.label,
                            fontSize = 11.5.sp,
                            fontWeight = if (optionIndex == index) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (optionIndex == index) LucernaColors.TextPrimary else LucernaColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = LucernaColors.TextTertiary,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun StorageRow(title: String, path: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 11.sp, color = LucernaColors.TextPrimary)
            Text(
                path,
                fontSize = 9.sp,
                color = LucernaColors.TextTertiary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        GhostButton("打开", onClick = onOpen)
    }
}
