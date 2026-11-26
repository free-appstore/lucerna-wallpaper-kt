package com.lucerna.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucerna.ui.theme.LucernaColors
import com.lucerna.ui.theme.LucernaMetrics
import com.lucerna.ui.theme.LucernaText

/** 半透明圆角卡片，面板里所有内容块的基础容器。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Int = 12,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LucernaColors.Card)
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(12.dp))
            .padding(padding.dp),
        content = content,
    )
}

/** 二级胶囊：分页下方那排选项的统一控件（尺寸见 [LucernaMetrics]）。 */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    count: Int? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(LucernaMetrics.SecondaryHeight)
            .clip(RoundedCornerShape(LucernaMetrics.SecondaryRadius))
            .background(if (selected) LucernaColors.AccentSoft else Color(0x12FFFFFF))
            .border(
                1.dp,
                if (selected) LucernaColors.Accent.copy(alpha = 0.65f) else LucernaColors.Border,
                RoundedCornerShape(LucernaMetrics.SecondaryRadius),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = LucernaMetrics.SecondaryHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        val labelColor = when {
            !enabled -> LucernaColors.TextTertiary
            selected -> LucernaColors.Accent
            else -> LucernaColors.TextSecondary
        }
        Text(
            text = if (count == null) {
                AnnotatedString(text)
            } else {
                buildAnnotatedString {
                    append(text)
                    withStyle(
                        SpanStyle(
                            fontSize = 9.sp,
                            color = if (selected) {
                                LucernaColors.Accent.copy(alpha = 0.65f)
                            } else {
                                LucernaColors.TextTertiary
                            },
                        ),
                    ) {
                        append(" $count")
                    }
                }
            },
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
        )
    }
}

/** 琥珀色主按钮。 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) LucernaColors.Accent else Color(0x22FFC46B))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = LucernaText.button,
            color = if (enabled) Color(0xFF1A1408) else LucernaColors.TextTertiary,
        )
    }
}

/** 次级描边按钮。 */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, LucernaColors.BorderStrong, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.5.sp, color = if (enabled) LucernaColors.TextSecondary else LucernaColors.TextTertiary)
    }
}

/** 设置页的一行：左侧标题与说明，右侧开关。 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, color = LucernaColors.TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 10.sp, color = LucernaColors.TextTertiary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.72f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF1A1408),
                checkedTrackColor = LucernaColors.Accent,
                uncheckedThumbColor = LucernaColors.TextSecondary,
                uncheckedTrackColor = LucernaColors.Track,
                uncheckedBorderColor = Color.Transparent,
                checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** 分隔线。 */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x10FFFFFF)),
    )
}

/** 空状态提示。 */
@Composable
fun EmptyHint(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 12.5.sp, color = LucernaColors.TextSecondary)
        Text(detail, fontSize = 10.5.sp, color = LucernaColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))
    }
}
