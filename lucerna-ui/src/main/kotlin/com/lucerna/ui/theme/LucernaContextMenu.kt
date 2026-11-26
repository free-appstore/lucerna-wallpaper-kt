package com.lucerna.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition

/**
 * 深色圆角的右键菜单。
 *
 * Compose 默认的 [androidx.compose.foundation.LightDefaultContextMenuRepresentation] 是白底黑字、
 * 菜单项高亮不带圆角，放在夜空面板上很突兀。这里替换成和面板一致的深色底、12dp 圆角外框，
 * 菜单项悬停 / 按下时用琥珀色圆角高亮，文字同时变成琥珀色，作为「选中」反馈。
 *
 * 通过 [LucernaTheme] 里的 `LocalContextMenuRepresentation` 注入，所有 `ContextMenuArea`
 * （图库卡片、导入页文件行、色值输入框等）都会自动生效。
 *
 * 所有常量与行组件都放在对象内部：不要在文件里新增顶层声明。
 * 顶层声明会被编进 `LucernaContextMenuKt` facade，菜单首次弹出时才解析这个类，
 * 热重载 / 旧构建产物里一旦缺少它就会抛 `NoClassDefFoundError`。
 */
object LucernaContextMenuRepresentation : ContextMenuRepresentation {

    /** 菜单外框与菜单项圆角，和面板里的卡片保持同一套手感。 */
    private val menuShape = RoundedCornerShape(12.dp)
    private val menuItemShape = RoundedCornerShape(8.dp)

    /** 菜单项统一高度与宽度范围（与 Material 菜单规格接近，但更紧凑）。 */
    private val menuItemHeight = 30.dp
    private val menuItemMinWidth = 132.dp
    private val menuItemMaxWidth = 280.dp

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return

        val entries = items()
        // 没有任何菜单项时直接收起，避免在光标处留下一个空弹层
        if (entries.isEmpty()) {
            SideEffect { state.status = ContextMenuState.Status.Closed }
            return
        }

        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .shadow(18.dp, menuShape, clip = false)
                    .clip(menuShape)
                    .background(LucernaColors.MenuSurface)
                    .border(1.dp, LucernaColors.BorderStrong, menuShape)
                    .width(IntrinsicSize.Max)
                    .padding(4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                entries.forEach { item ->
                    MenuRow(
                        item = item,
                        onDismiss = { state.status = ContextMenuState.Status.Closed },
                    )
                }
            }
        }
    }

    @Composable
    private fun MenuRow(
        item: ContextMenuItem,
        onDismiss: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val pressed by interaction.collectIsPressedAsState()
        val selected = item.enabled && (hovered || pressed)

        val background by animateColorAsState(
            targetValue = if (selected) LucernaColors.AccentSoft else Color.Transparent,
            animationSpec = tween(durationMillis = 120),
            label = "contextMenuItemBackground",
        )
        val labelColor by animateColorAsState(
            targetValue = when {
                !item.enabled -> LucernaColors.TextTertiary
                selected -> LucernaColors.Accent
                else -> LucernaColors.TextPrimary
            },
            animationSpec = tween(durationMillis = 120),
            label = "contextMenuItemLabel",
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minWidth = menuItemMinWidth, maxWidth = menuItemMaxWidth)
                .height(menuItemHeight)
                .clip(menuItemShape)
                .background(background)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = item.enabled,
                ) {
                    onDismiss()
                    item.onClick()
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = item.label,
                fontSize = 12.sp,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
