@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.lucerna.ui.pages

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.Rgb
import com.lucerna.core.render.ColorRenderer
import com.lucerna.ui.components.*
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.theme.LucernaColors
import com.lucerna.ui.theme.LucernaMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

/** 三张卡片的标题行高度：方向、阶数、颜色的 label 左侧与顶部保持一致。 */
private val CardHeaderHeight = 24.dp

/** 方向 / 阶数一行的高度：够放 22dp 的九宫格，从颜色卡手里让出高度。 */
private val BottomRowHeight = 114.dp

/** 「色彩」页：纯色 / 渐变 / 色阶编辑器。 */
@Composable
fun ColorPage(state: LucernaState) {
    val fill = state.colorDraft
    Column(Modifier.fillMaxSize()) {
        // 预览与类型选择固定在顶部，下面的编辑区正好铺满剩余高度
        ColorPreview(fill, onReveal = state::openRenders)
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LucernaMetrics.SecondaryGap),
        ) {
            ColorKind.entries.forEach { kind ->
                PillButton(
                    text = kind.label,
                    selected = fill.kind == kind,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        state.updateColorDraft(fill.copy(kind = kind).normalized())
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val withAngle = fill.kind.usesAngle
        ColorEditorCard(
            fill = fill,
            state = state,
            stretch = withAngle,
            modifier = if (withAngle) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (withAngle) {
            val withSteps = fill.kind == ColorKind.STEPS
            Row(
                modifier = Modifier.height(BottomRowHeight).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DirectionCard(
                    fill = fill,
                    state = state,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                )
                if (withSteps) {
                    StepsCard(
                        fill = fill,
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

        PrimaryButton(
            text = "添加并应用",
            modifier = Modifier.fillMaxWidth(),
            onClick = { state.addAndApplyColor(fill) },
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** 颜色槽、分量滑杆与色值输入；色块多了自动换行，加减固定在右侧。 */
@Composable
private fun ColorEditorCard(
    fill: ColorFill,
    state: LucernaState,
    stretch: Boolean,
    modifier: Modifier = Modifier,
) {
    val index = state.selectedColorIndex.coerceIn(0, fill.wellCount - 1)
    val current = fill.colorAt(index)
    var decimal by remember { mutableStateOf(false) }
    val applyColor: (Rgb) -> Unit = { rgb -> state.updateColorDraft(fill.withColor(index, rgb)) }

    GlassCard(modifier, padding = 10) {
        Row(
            modifier = Modifier.fillMaxWidth().height(CardHeaderHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("颜色", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
            Spacer(Modifier.weight(1f))
            if (decimal) {
                RgbInputs(current, applyColor)
            } else {
                HexColorInput(current, applyColor)
            }
            Spacer(Modifier.width(6.dp))
            NumberModeToggle(decimal = decimal) { decimal = it }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(fill.wellCount) { wellIndex ->
                    ColorWell(
                        color = fill.colorAt(wellIndex),
                        selected = state.selectedColorIndex == wellIndex,
                        onClick = { state.selectedColorIndex = wellIndex },
                    )
                }
            }
            if (fill.kind != ColorKind.SOLID) {
                Spacer(Modifier.width(8.dp))
                SmallRoundButton(enabled = fill.canAddColor(), positive = true) {
                    state.updateColorDraft(fill.addingColor())
                    state.selectedColorIndex = fill.colors.size
                }
                Spacer(Modifier.width(4.dp))
                SmallRoundButton(enabled = fill.canRemoveColor(), positive = false) {
                    val removed = fill.removingColor(state.selectedColorIndex)
                    state.updateColorDraft(removed)
                    state.selectedColorIndex = state.selectedColorIndex.coerceAtMost(removed.wellCount - 1)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 滑杆本身是渐变：R 条从「G、B 不变、R=0」到「R=1」，G / B 同理
        Column(
            modifier = (if (stretch) Modifier.weight(1f) else Modifier).fillMaxWidth(),
            verticalArrangement = if (stretch) Arrangement.SpaceEvenly else Arrangement.spacedBy(6.dp),
        ) {
            ColorComponentSlider(
                label = "R",
                value = current.r,
                tint = Color(0xFFFF6B6B),
                gradient = listOf(Color(0f, current.g, current.b), Color(1f, current.g, current.b)),
                modifier = Modifier.height(24.dp),
            ) { value -> applyColor(current.copy(r = value)) }
            ColorComponentSlider(
                label = "G",
                value = current.g,
                tint = Color(0xFF7EDB95),
                gradient = listOf(Color(current.r, 0f, current.b), Color(current.r, 1f, current.b)),
                modifier = Modifier.height(24.dp),
            ) { value -> applyColor(current.copy(g = value)) }
            ColorComponentSlider(
                label = "B",
                value = current.b,
                tint = Color(0xFF6BA8FF),
                gradient = listOf(Color(current.r, current.g, 0f), Color(current.r, current.g, 1f)),
                modifier = Modifier.height(24.dp),
            ) { value -> applyColor(current.copy(b = value)) }
        }
    }
}

/** 十六进制 / 十进制切换。 */
@Composable
private fun NumberModeToggle(decimal: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x0CFFFFFF))
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        ModeSegment("16进制", selected = !decimal) { onChange(false) }
        ModeSegment("10进制", selected = decimal) { onChange(true) }
    }
}

@Composable
private fun ModeSegment(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) LucernaColors.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 8.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) LucernaColors.Accent else LucernaColors.TextTertiary,
        )
    }
}

/** #FFFFFF：'#' 是前置文本，只有 6 位十六进制可输入。 */
@Composable
private fun HexColorInput(color: Rgb, onColor: (Rgb) -> Unit) {
    var text by remember { mutableStateOf(color.toHex().removePrefix("#")) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(color) {
        if (!focused) text = color.toHex().removePrefix("#")
    }
    Row(
        modifier = Modifier
            .width(104.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x12FFFFFF))
            .border(
                1.dp,
                if (focused) LucernaColors.Accent.copy(alpha = 0.65f) else LucernaColors.Border,
                RoundedCornerShape(7.dp),
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", fontSize = 12.sp, color = LucernaColors.TextTertiary)
        Spacer(Modifier.width(3.dp))
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val clean = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6).uppercase()
                text = clean
                if (clean.length == 6) Rgb.parseHex(clean)?.let(onColor)
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.5.sp, color = LucernaColors.TextPrimary),
            cursorBrush = SolidColor(LucernaColors.Accent),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focus ->
                    focused = focus.isFocused
                    if (!focus.isFocused) {
                        if (text.length == 6) Rgb.parseHex(text)?.let(onColor)
                        else text = color.toHex().removePrefix("#")
                    }
                },
        )
    }
}

/** 十进制模式：R / G / B 三个 0–255 输入框，支持上下方向键。 */
@Composable
private fun RgbInputs(color: Rgb, onColor: (Rgb) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        RgbChannelInput("R", (color.r * 255f).roundToInt(), Color(0xFFFF6B6B)) { value ->
            onColor(color.copy(r = value / 255f))
        }
        RgbChannelInput("G", (color.g * 255f).roundToInt(), Color(0xFF7EDB95)) { value ->
            onColor(color.copy(g = value / 255f))
        }
        RgbChannelInput("B", (color.b * 255f).roundToInt(), Color(0xFF6BA8FF)) { value ->
            onColor(color.copy(b = value / 255f))
        }
    }
}

@Composable
private fun RgbChannelInput(label: String, value: Int, tint: Color, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 9.5.sp, color = tint, modifier = Modifier.width(10.dp))
        NumericField(
            value = value,
            min = 0,
            max = 255,
            modifier = Modifier.width(42.dp),
            fontSize = 12.sp,
            fieldHeight = 24.dp,
            onValue = onValue,
        )
    }
}

/** 九宫格里的 8 个方向，中心留一个圆点；角度与渐变线一致。 */
private val DirectionCells: List<Pair<String?, Float>> = listOf(
    "↖" to 225f, "↑" to 270f, "↗" to 315f,
    "←" to 180f, null to 0f, "→" to 0f,
    "↙" to 135f, "↓" to 90f, "↘" to 45f,
)

/** 角度圆盘 + 读数 + 右侧 3x3 方向键；九宫格上下左右各留 10dp，与卡片等高。 */
@Composable
private fun DirectionCard(fill: ColorFill, state: LucernaState, modifier: Modifier = Modifier) {
    GlassCard(modifier, padding = 10) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxHeight()) {
                // 与颜色 / 阶数相同的 24dp 标题行，label 左、上完全对齐
                Box(
                    modifier = Modifier.fillMaxWidth().height(CardHeaderHeight),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("角度", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AngleDial(angle = fill.angle) { angle ->
                            state.updateColorDraft(fill.copy(angle = angle.toFloat()))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${fill.angle.roundToInt().mod(360)}°",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LucernaColors.Accent,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DirectionCells.chunked(3).forEach { rowCells ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        rowCells.forEach { (label, angle) ->
                            DirectionCell(label, angle, fill, state)
                        }
                    }
                }
            }
        }
    }
}

/** 九宫格里的一个方形方向键；label 为 null 时是中心圆点。 */
@Composable
private fun DirectionCell(label: String?, angle: Float, fill: ColorFill, state: LucernaState) {
    if (label == null) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(LucernaColors.BorderStrong))
        }
        return
    }
    val selected = fill.angle.roundToInt().mod(360) == angle.roundToInt()
    Box(
        modifier = Modifier
            .size(30.dp)
            // clip 与 border 用同一个 4dp 圆角，选中时四个角不会被切掉
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) LucernaColors.AccentSoft else Color(0x10FFFFFF))
            .border(
                1.dp,
                if (selected) LucernaColors.Accent.copy(alpha = 0.65f) else LucernaColors.Border,
                RoundedCornerShape(4.dp),
            )
            .clickable { state.updateColorDraft(fill.copy(angle = angle)) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) LucernaColors.Accent else LucernaColors.TextSecondary,
        )
    }
}

/** 色阶阶数：标题与方向对齐，数量居中并可点按 / 长按调节，右下角提示可用区间。 */
@Composable
private fun StepsCard(fill: ColorFill, state: LucernaState, modifier: Modifier = Modifier) {
    val minSteps = fill.colors.size.coerceIn(ColorFill.MIN_STEPS, ColorFill.MAX_STEPS)
    GlassCard(modifier, padding = 10) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(CardHeaderHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("阶数", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumericField(
                        value = fill.steps,
                        min = minSteps,
                        max = ColorFill.MAX_STEPS,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        fieldHeight = 28.dp,
                        onValue = { steps -> state.updateColorDraft(fill.copy(steps = steps).normalized()) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        StepperButton(enabled = fill.steps < ColorFill.MAX_STEPS, up = true, description = "阶数加一") {
                            val draft = state.colorDraft
                            state.updateColorDraft(draft.copy(steps = draft.steps + 1).normalized())
                        }
                        StepperButton(enabled = fill.steps > minSteps, up = false, description = "阶数减一") {
                            val draft = state.colorDraft
                            state.updateColorDraft(draft.copy(steps = draft.steps - 1).normalized())
                        }
                    }
                }
            }
            // 颜色多到 20 个时区间只剩一个值，直接显示 20 阶
            val rangeHint = if (minSteps >= ColorFill.MAX_STEPS) {
                "${ColorFill.MAX_STEPS} 阶"
            } else {
                "$minSteps–${ColorFill.MAX_STEPS} 阶"
            }
            Text(
                rangeHint,
                fontSize = 9.sp,
                color = LucernaColors.TextTertiary,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** 上下小按钮：点一下走一格，按住后每 60ms 连续走。 */
@Composable
private fun StepperButton(enabled: Boolean, up: Boolean, description: String, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val tint = if (enabled) LucernaColors.TextSecondary else LucernaColors.TextTertiary
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(4.dp))
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    step()
                    val repeat = scope.launch {
                        delay(400)
                        while (true) {
                            step()
                            delay(60)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(6.dp)) {
            val path = Path().apply {
                if (up) {
                    moveTo(0f, size.height)
                    lineTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                } else {
                    moveTo(0f, 0f)
                    lineTo(size.width / 2f, size.height)
                    lineTo(size.width, 0f)
                }
                close()
            }
            drawPath(path, tint)
        }
    }
}

/** 数字输入框：只收数字，上下方向键加 / 减，失焦时夹到范围内。 */
@Composable
private fun NumericField(
    value: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    fieldHeight: Dp = 24.dp,
    onValue: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    // 值变化（上下按钮、增删颜色、载入其它色彩）就同步显示；
    // 点击按钮不会让输入框失焦，所以这里不能用 focused 做条件
    LaunchedEffect(value) { text = value.toString() }

    Box(
        modifier = modifier
            .height(fieldHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x12FFFFFF))
            .border(
                1.dp,
                if (focused) LucernaColors.Accent.copy(alpha = 0.65f) else LucernaColors.Border,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(max.toString().length)
                text = digits
                digits.toIntOrNull()?.let { parsed ->
                    if (parsed in min..max) onValue(parsed)
                }
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = fontSize, color = LucernaColors.TextPrimary, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(LucernaColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    focused = focus.isFocused
                    if (!focus.isFocused) {
                        val clamped = (text.toIntOrNull() ?: value).coerceIn(min, max)
                        text = clamped.toString()
                        onValue(clamped)
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val delta = when (event.key) {
                        Key.DirectionUp -> 1
                        Key.DirectionDown -> -1
                        else -> 0
                    }
                    if (delta == 0) return@onPreviewKeyEvent false
                    val next = (text.toIntOrNull() ?: value) + delta
                    if (next in min..max) {
                        text = next.toString()
                        onValue(next)
                    }
                    true
                },
        )
    }
}

@Composable
private fun ColorPreview(fill: ColorFill, onReveal: () -> Unit) {
    val bitmap = remember(fill) {
        ColorRenderer.render(fill, 720, 405).toComposeImageBitmap()
    }
    val menuState = remember { ContextMenuState() }
    ContextMenuArea(
        items = { listOf(ContextMenuItem("打开文件位置") { onReveal() }) },
        state = menuState,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, LucernaColors.Border, RoundedCornerShape(12.dp)),
        ) {
            Image(bitmap, contentDescription = "色彩预览", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                fill.summary(),
                fontSize = 9.5.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ColorWell(color: Rgb, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(color.r, color.g, color.b))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else Color(0x33FFFFFF),
                CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SmallRoundButton(enabled: Boolean, positive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, LucernaColors.Border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) LucernaColors.TextSecondary else LucernaColors.TextTertiary
        if (positive) PlusGlyph(Modifier.size(8.dp), color = tint)
        else MinusGlyph(Modifier.size(8.dp), color = tint)
    }
}

/** 系统 Slider 的拇指与轨道尺寸（M3）：轨道 16dp、拇指 4dp 宽、四周 6dp 间距。 */
private val TrackHeight = 16.dp
private val TrackThumbWidth = 4.dp
private val TrackThumbGap = 6.dp

/** 用系统 Slider 的样式，只把轨道换成渐变。 */
@Composable
private fun ColorComponentSlider(
    label: String,
    value: Float,
    tint: Color,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val sliderColors = SliderDefaults.colors(
        thumbColor = LucernaColors.Accent,
        activeTrackColor = Color.Transparent,
        inactiveTrackColor = Color.Transparent,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = tint, modifier = Modifier.width(14.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            valueRange = 0f..1f,
            colors = sliderColors,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = sliderColors,
                    enabled = true,
                )
            },
            track = { GradientTrack(value.coerceIn(0f, 1f), gradient) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics { contentDescription = "$label 分量滑杆" },
        )
        Text(
            "${(value.coerceIn(0f, 1f) * 255).roundToInt()}",
            fontSize = 9.5.sp,
            color = LucernaColors.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(26.dp),
        )
    }
}

/**
 * 与 SliderDefaults.Track 相同的几何：16dp 高的圆角轨道、拇指两侧各留 8dp 缺口、
 * 末端一个 4dp 的停靠点；区别只是轨道填充用渐变。
 */
@Composable
private fun GradientTrack(fraction: Float, gradient: List<Color>) {
    Canvas(Modifier.fillMaxWidth().height(TrackHeight)) {
        val height = size.height
        val outerCorner = height / 2f
        val innerCorner = 2.dp.toPx()
        val halfGap = TrackThumbWidth.toPx() / 2f + TrackThumbGap.toPx()
        val valueX = size.width * fraction.coerceIn(0f, 1f)
        val brush = Brush.horizontalGradient(gradient, startX = 0f, endX = size.width)

        val activeEnd = valueX - halfGap
        if (activeEnd > 0f) {
            drawTrackPart(brush, start = 0f, end = activeEnd, height = height, startCorner = outerCorner, endCorner = innerCorner)
        }
        val inactiveStart = valueX + halfGap
        if (inactiveStart < size.width) {
            drawTrackPart(brush, start = inactiveStart, end = size.width, height = height, startCorner = innerCorner, endCorner = outerCorner)
        }
        // 轨道末端的停靠点，和默认 Slider 一样距端点 8dp
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = 2.dp.toPx(),
            center = Offset(size.width - outerCorner, height / 2f),
        )
    }
}

private fun DrawScope.drawTrackPart(
    brush: Brush,
    start: Float,
    end: Float,
    height: Float,
    startCorner: Float,
    endCorner: Float,
) {
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(Offset(start, 0f), Size(end - start, height)),
                topLeft = CornerRadius(startCorner),
                topRight = CornerRadius(endCorner),
                bottomRight = CornerRadius(endCorner),
                bottomLeft = CornerRadius(startCorner),
            ),
        )
    }
    drawPath(path, brush)
}

/** 可拖动的方向圆盘，0° 指向右侧，顺时针增长。 */
@Composable
private fun AngleDial(angle: Float, onChange: (Int) -> Unit) {
    val onAngle by rememberUpdatedState(onChange)
    Canvas(
        modifier = Modifier
            .size(58.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onAngle(offset.toAngle(size.width, size.height))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onAngle(change.position.toAngle(size.width, size.height))
                }
            },
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(radius, radius)
        drawCircle(color = Color(0x12FFFFFF), radius = radius)
        drawCircle(color = Color(0x33FFFFFF), radius = radius, style = Stroke(1f))
        // 刻度
        repeat(12) { index ->
            val theta = index * 30.0 * PI / 180.0
            val inner = radius * 0.78f
            val outer = radius * 0.9f
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(center.x + (cos(theta) * inner).toFloat(), center.y + (sin(theta) * inner).toFloat()),
                end = Offset(center.x + (cos(theta) * outer).toFloat(), center.y + (sin(theta) * outer).toFloat()),
                strokeWidth = 1.2f,
            )
        }
        val theta = Math.toRadians(angle.roundToInt().mod(360).toDouble())
        val tip = Offset(
            center.x + (cos(theta) * radius * 0.66).toFloat(),
            center.y + (sin(theta) * radius * 0.66).toFloat(),
        )
        drawLine(
            color = LucernaColors.Accent,
            start = center,
            end = tip,
            strokeWidth = 2.4f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(color = LucernaColors.Accent, radius = radius * 0.13f, center = tip)
        drawCircle(color = Color(0xFF1A1D27), radius = radius * 0.09f, center = center)
    }
}

private fun Offset.toAngle(width: Int, height: Int): Int {
    val cx = width / 2f
    val cy = height / 2f
    val degrees = Math.toDegrees(atan2(y - cy, x - cx).toDouble()).toInt()
    return degrees.mod(360)
}
