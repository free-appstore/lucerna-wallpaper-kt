@file:OptIn(ExperimentalFoundationApi::class)

package com.lucerna.ui.pages

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.Rgb
import com.lucerna.core.render.ColorRenderer
import com.lucerna.ui.components.*
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.theme.LucernaColors
import kotlin.math.*

/** 「色彩」页：纯色 / 渐变 / 色阶编辑器。 */
@Composable
fun ColorPage(state: LucernaState) {
    val fill = state.colorDraft
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
        ) {
            ColorPreview(fill, onReveal = state::openRenders)
            Spacer(Modifier.height(12.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                Text("类型", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
            Spacer(Modifier.height(10.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("颜色", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(fill.summary(), fontSize = 9.5.sp, color = LucernaColors.TextTertiary)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    repeat(fill.wellCount) { index ->
                        ColorWell(
                            color = fill.colorAt(index),
                            selected = state.selectedColorIndex == index,
                            onClick = { state.selectedColorIndex = index },
                        )
                    }
                    if (fill.kind != ColorKind.SOLID) {
                        SmallRoundButton(enabled = fill.canAddColor(), positive = true) {
                            state.updateColorDraft(fill.addingColor())
                            state.selectedColorIndex = fill.colors.size
                        }
                        SmallRoundButton(enabled = fill.canRemoveColor(), positive = false) {
                            val removed = fill.removingColor(state.selectedColorIndex)
                            state.updateColorDraft(removed)
                            state.selectedColorIndex = state.selectedColorIndex.coerceAtMost(removed.wellCount - 1)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val index = state.selectedColorIndex.coerceIn(0, fill.wellCount - 1)
                val current = fill.colorAt(index)
                ColorComponentSlider("R", current.r, Color(0xFFFF6B6B)) { value ->
                    state.updateColorDraft(fill.withColor(index, current.copy(r = value)))
                }
                ColorComponentSlider("G", current.g, Color(0xFF7EDB95)) { value ->
                    state.updateColorDraft(fill.withColor(index, current.copy(g = value)))
                }
                ColorComponentSlider("B", current.b, Color(0xFF6BA8FF)) { value ->
                    state.updateColorDraft(fill.withColor(index, current.copy(b = value)))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("色值", fontSize = 10.sp, color = LucernaColors.TextTertiary, modifier = Modifier.width(34.dp))
                    HexField(
                        value = current.toHex(),
                        onColor = { rgb -> state.updateColorDraft(fill.withColor(index, rgb)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (fill.kind.usesAngle) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("方向", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
                        Spacer(Modifier.weight(1f))
                        Text("${fill.angle.roundToInt().mod(360)}°", fontSize = 10.sp, color = LucernaColors.Accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AngleDial(angle = fill.angle) { angle ->
                            state.updateColorDraft(fill.copy(angle = angle.toFloat()))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AnglePreset("→", 0f, fill, state)
                                AnglePreset("↓", 90f, fill, state)
                                AnglePreset("↘", 45f, fill, state)
                                AnglePreset("↗", 315f, fill, state)
                            }
                            Text(
                                "拖动圆盘可切换到任意角度",
                                fontSize = 9.5.sp,
                                color = LucernaColors.TextTertiary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (fill.kind == ColorKind.STEPS) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("阶梯数量", fontSize = 10.5.sp, color = LucernaColors.TextTertiary)
                        Spacer(Modifier.weight(1f))
                        Text("${fill.steps} 阶", fontSize = 10.sp, color = LucernaColors.Accent)
                    }
                    Slider(
                        value = fill.steps.toFloat(),
                        onValueChange = { value ->
                            state.updateColorDraft(fill.copy(steps = value.roundToInt()))
                        },
                        valueRange = ColorFill.MIN_STEPS.toFloat()..ColorFill.MAX_STEPS.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = LucernaColors.Accent,
                            activeTrackColor = LucernaColors.Accent,
                            inactiveTrackColor = LucernaColors.Track,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        PrimaryButton(
            text = "应用色彩壁纸",
            modifier = Modifier.fillMaxWidth(),
            onClick = { state.applyColor(state.colorDraft) },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ColorPreview(fill: ColorFill, onReveal: () -> Unit) {
    val bitmap = remember(fill) {
        ColorRenderer.render(fill, 720, 340).toComposeImageBitmap()
    }
    val menuState = remember { ContextMenuState() }
    ContextMenuArea(
        items = { listOf(ContextMenuItem("打开文件位置") { onReveal() }) },
        state = menuState,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
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
            .size(26.dp)
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
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, LucernaColors.Border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) LucernaColors.TextSecondary else LucernaColors.TextTertiary
        if (positive) PlusGlyph(Modifier.size(10.dp), color = tint)
        else MinusGlyph(Modifier.size(10.dp), color = tint)
    }
}

@Composable
private fun ColorComponentSlider(
    label: String,
    value: Float,
    tint: Color,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = LucernaColors.TextTertiary, modifier = Modifier.width(18.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = LucernaColors.Track,
            ),
            modifier = Modifier.weight(1f).height(30.dp),
        )
        Text(
            "${(value.coerceIn(0f, 1f) * 255).roundToInt()}",
            fontSize = 9.5.sp,
            color = LucernaColors.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
    }
}

@Composable
private fun HexField(value: String, onColor: (Rgb) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value) }
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x12FFFFFF))
            .border(1.dp, LucernaColors.Border, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.take(7)
                Rgb.parseHex(text)?.let(onColor)
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 10.5.sp, color = LucernaColors.TextPrimary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AnglePreset(label: String, angle: Float, fill: ColorFill, state: LucernaState) {
    PillButton(
        text = label,
        selected = fill.angle.roundToInt().mod(360) == angle.roundToInt(),
        modifier = Modifier.width(38.dp),
        onClick = { state.updateColorDraft(fill.copy(angle = angle)) },
    )
}

/** 可拖动的方向圆盘，0° 指向右侧，顺时针增长。 */
@Composable
private fun AngleDial(angle: Float, onChange: (Int) -> Unit) {
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange(offset.toAngle(size.width, size.height))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(change.position.toAngle(size.width, size.height))
                }
            },
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(radius, radius)
        drawCircle(color = Color(0x12FFFFFF), radius = radius)
        drawCircle(color = Color(0x33FFFFFF), radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
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
