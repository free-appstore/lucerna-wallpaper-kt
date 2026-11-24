package com.lucerna.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.lucerna.ui.theme.LucernaColors

/**
 * 萤火虫 logo：琥珀色腹部发光，半透明翅膀，尾部一点光晕。
 */
@Composable
fun FireflyLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.size(38.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f

        // 尾部光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x66FFC46B), Color(0x00FFC46B)),
                center = Offset(cx, h * 0.68f),
                radius = w * 0.46f,
            ),
            radius = w * 0.46f,
            center = Offset(cx, h * 0.68f),
        )

        // 翅膀，左右各一只，带一点透视
        rotate(-24f, Offset(cx, h * 0.42f)) {
            drawOval(
                brush = Brush.verticalGradient(
                    listOf(Color(0x88FFE9B8), Color(0x22FFC46B)),
                ),
                topLeft = Offset(cx - w * 0.10f, h * 0.12f),
                size = Size(w * 0.46f, h * 0.34f),
            )
        }
        rotate(24f, Offset(cx, h * 0.42f)) {
            drawOval(
                brush = Brush.verticalGradient(
                    listOf(Color(0x88FFE9B8), Color(0x22FFC46B)),
                ),
                topLeft = Offset(cx - w * 0.36f, h * 0.12f),
                size = Size(w * 0.46f, h * 0.34f),
            )
        }

        // 身体：头部偏暗，腹部越往下越亮
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF6B5126), Color(0xFFFFD98A), Color(0xFFFFB84D)),
                startY = h * 0.28f,
                endY = h * 0.86f,
            ),
            topLeft = Offset(cx - w * 0.085f, h * 0.26f),
            size = Size(w * 0.17f, h * 0.58f),
        )

        // 头部
        drawCircle(color = Color(0xFF4A3818), radius = w * 0.065f, center = Offset(cx, h * 0.27f))

        // 触角
        drawLine(
            color = Color(0xAAFFE3A3),
            start = Offset(cx - w * 0.035f, h * 0.235f),
            end = Offset(cx - w * 0.14f, h * 0.12f),
            strokeWidth = w * 0.022f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xAAFFE3A3),
            start = Offset(cx + w * 0.035f, h * 0.235f),
            end = Offset(cx + w * 0.14f, h * 0.12f),
            strokeWidth = w * 0.022f,
            cap = StrokeCap.Round,
        )

        // 腹部高光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xCCFFF3D6), Color(0x00FFF3D6)),
                center = Offset(cx, h * 0.66f),
                radius = w * 0.16f,
            ),
            radius = w * 0.16f,
            center = Offset(cx, h * 0.66f),
        )
    }
}

/** 关闭图标：两条交叉的线。 */
@Composable
fun CloseGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextSecondary) {
    Canvas(modifier.size(14.dp)) {
        val s = size.minDimension
        val pad = s * 0.16f
        val width = s * 0.11f
        drawLine(color, Offset(pad, pad), Offset(s - pad, s - pad), strokeWidth = width, cap = StrokeCap.Round)
        drawLine(color, Offset(s - pad, pad), Offset(pad, s - pad), strokeWidth = width, cap = StrokeCap.Round)
    }
}

/** 电源键图标：顶部带缺口的圆环加一条竖线。 */
@Composable
fun PowerGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.Danger) {
    Canvas(modifier.size(15.dp)) {
        val w = size.width
        val stroke = w * 0.12f
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, w * 0.16f),
            size = Size(w * 0.72f, w * 0.72f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color,
            Offset(w * 0.5f, w * 0.06f),
            Offset(w * 0.5f, w * 0.46f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 折叠菜单的箭头，默认朝右，展开时由调用方旋转 90°。 */
@Composable
fun ChevronGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextTertiary) {
    Canvas(modifier.size(10.dp)) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * 0.34f, w * 0.20f)
            lineTo(w * 0.68f, w * 0.50f)
            lineTo(w * 0.34f, w * 0.80f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 播放三角。 */
@Composable
fun PlayGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(12.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.12f)
            lineTo(size.width * 0.88f, size.height * 0.5f)
            lineTo(size.width * 0.22f, size.height * 0.88f)
            close()
        }
        drawPath(path, color)
    }
}

/** 文件夹图标。 */
@Composable
fun FolderGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.Accent) {
    Canvas(modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.26f)
            lineTo(w * 0.40f, h * 0.26f)
            lineTo(w * 0.50f, h * 0.38f)
            lineTo(w * 0.92f, h * 0.38f)
            lineTo(w * 0.92f, h * 0.84f)
            lineTo(w * 0.08f, h * 0.84f)
            close()
        }
        drawPath(path, color.copy(alpha = color.alpha * 0.9f))
    }
}

/** 图片图标：山与太阳。 */
@Composable
fun PhotoGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.08f, size.height * 0.16f),
            size = Size(size.width * 0.84f, size.height * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f),
            style = Stroke(width = size.width * 0.09f),
        )
        drawCircle(color, radius = size.width * 0.09f, center = Offset(size.width * 0.34f, size.height * 0.38f))
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.74f)
            lineTo(size.width * 0.44f, size.height * 0.48f)
            lineTo(size.width * 0.64f, size.height * 0.68f)
            lineTo(size.width * 0.76f, size.height * 0.58f)
            lineTo(size.width * 0.84f, size.height * 0.66f)
        }
        drawPath(path, color, style = Stroke(width = size.width * 0.08f, cap = StrokeCap.Round))
    }
}

/** 视频图标。 */
@Composable
fun VideoGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextSecondary) {
    Canvas(modifier.size(16.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.06f, size.height * 0.22f),
            size = Size(size.width * 0.62f, size.height * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.1f),
            style = Stroke(width = size.width * 0.09f),
        )
        val path = Path().apply {
            moveTo(size.width * 0.72f, size.height * 0.40f)
            lineTo(size.width * 0.94f, size.height * 0.28f)
            lineTo(size.width * 0.94f, size.height * 0.72f)
            lineTo(size.width * 0.72f, size.height * 0.60f)
            close()
        }
        drawPath(path, color)
    }
}

/** 返回上一级。 */
@Composable
fun UpGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextSecondary) {
    Canvas(modifier.size(14.dp)) {
        val w = size.width
        drawLine(color, Offset(w * 0.5f, w * 0.82f), Offset(w * 0.5f, w * 0.2f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.2f, w * 0.48f), Offset(w * 0.5f, w * 0.18f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.8f, w * 0.48f), Offset(w * 0.5f, w * 0.18f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
    }
}

/** 加号。 */
@Composable
fun PlusGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextSecondary) {
    Canvas(modifier.size(12.dp)) {
        val w = size.width
        drawLine(color, Offset(w * 0.5f, w * 0.16f), Offset(w * 0.5f, w * 0.84f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.16f, w * 0.5f), Offset(w * 0.84f, w * 0.5f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
    }
}

/** 减号。 */
@Composable
fun MinusGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextSecondary) {
    Canvas(modifier.size(12.dp)) {
        val w = size.width
        drawLine(color, Offset(w * 0.16f, w * 0.5f), Offset(w * 0.84f, w * 0.5f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
    }
}

/** 定位图标。 */
@Composable
fun PinGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.TextSecondary) {
    Canvas(modifier.size(15.dp)) {
        val w = size.width
        drawCircle(color, radius = w * 0.3f, center = Offset(w * 0.5f, w * 0.4f), style = Stroke(width = w * 0.1f))
        drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.5f, w * 0.4f))
        drawLine(color, Offset(w * 0.5f, w * 0.7f), Offset(w * 0.5f, w * 0.9f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}

/** 勾选。 */
@Composable
fun CheckGlyph(modifier: Modifier = Modifier, color: Color = Color(0xFF1A1408)) {
    Canvas(modifier.size(11.dp)) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * 0.12f, w * 0.52f)
            lineTo(w * 0.42f, w * 0.82f)
            lineTo(w * 0.9f, w * 0.2f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.16f, cap = StrokeCap.Round))
    }
}

/** 齿轮图标，用于设置页标题。 */
@Composable
fun GearGlyph(modifier: Modifier = Modifier, color: Color = LucernaColors.Accent) {
    Canvas(modifier.size(15.dp)) {
        val w = size.width
        drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, w * 0.5f), style = Stroke(width = w * 0.12f))
        repeat(8) { index ->
            rotate(index * 45f, Offset(w * 0.5f, w * 0.5f)) {
                drawLine(
                    color,
                    Offset(w * 0.5f, w * 0.06f),
                    Offset(w * 0.5f, w * 0.2f),
                    strokeWidth = w * 0.12f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
