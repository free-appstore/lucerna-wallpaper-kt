package com.lucerna.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 面板的视觉语言：夜空底色 + 琥珀色荧光。 */
object LucernaColors {
    val Accent = Color(0xFFFFC46B)
    val AccentDeep = Color(0xFFF2A24C)
    val AccentSoft = Color(0x33FFC46B)
    val PanelTop = Color(0xFF191C26)
    val PanelBottom = Color(0xFF0A0C12)
    val Card = Color(0x0DFFFFFF)
    val CardStrong = Color(0x14FFFFFF)
    val Border = Color(0x1FFFFFFF)
    val BorderStrong = Color(0x2EFFFFFF)
    val TextPrimary = Color(0xFFF7F2E8)
    val TextSecondary = Color(0xA6FFFFFF)
    val TextTertiary = Color(0x66FFFFFF)
    val Danger = Color(0xFFFF7A7A)
    val Good = Color(0xFF8FE3B0)
    val Track = Color(0x26FFFFFF)

    val panelBrush = Brush.verticalGradient(listOf(PanelTop, PanelBottom))
}

/** 面板字号层级。 */
object LucernaText {
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LucernaColors.TextPrimary)
    val subtitle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = LucernaColors.TextTertiary)
    val section = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LucernaColors.TextSecondary)
    val body = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = LucernaColors.TextPrimary)
    val caption = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Normal, color = LucernaColors.TextTertiary)
    val button = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1408))
}

@Composable
fun LucernaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LucernaColors.Accent,
            onPrimary = Color(0xFF1A1408),
            secondary = LucernaColors.AccentDeep,
            background = LucernaColors.PanelBottom,
            surface = LucernaColors.PanelTop,
            onSurface = LucernaColors.TextPrimary,
            onSurfaceVariant = LucernaColors.TextSecondary,
            error = LucernaColors.Danger,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}
