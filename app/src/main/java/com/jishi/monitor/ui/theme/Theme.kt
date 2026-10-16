package com.jishi.monitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 单浅色主题（FR-7 / AC-20）。明确不做深色模式：无论系统设置如何，始终使用浅色。 */
object JishiColors {
    val BgTop = Color(0xFFF7F9FC)
    val BgBottom = Color(0xFFE9EFF8)
    val Card = Color(0xFFFFFFFF)
    val Divider = Color(0xFFE5EAF2)
    val TextPrimary = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF5A6B85)
    /** 弱化文字同样需满足正文对比度下限，故不用设计稿的 #93A1B5（仅 2.6:1）。 */
    val TextWeak = Color(0xFF556377)
    // ---- 主色：蓝（按甲方要求回到蓝色系） ----
    val AccentStart = Color(0xFF3B82F6)
    val AccentEnd = Color(0xFF22D3EE)
    /** 装饰用绿（点/条），不作为文字色。 */
    val Online = Color(0xFF12B76A)
    /** 时间轴录像条：小米那套用暖黄标识"有录像"，这里取同一类型。 */
    val Timeline = Color(0xFFFFB300)
    val TimelineTrack = Color(0xFFEDEFF3)
    /** 关键信息用蓝（对白底 ≥4.5:1）。 */
    val AccentText = Color(0xFF1D4ED8)
    /** 文字用绿：#06653A 对白底约 7.4:1，满足 FR-7.3 关键信息 ≥7:1。 */
    val OnlineText = Color(0xFF06653A)
    /** 文字用灰蓝：#64748B 对白底约 4.8:1，满足正文 ≥4.5:1。 */
    val TextMuted = Color(0xFF64748B)
    /** 主按钮实色：#1D4ED8 配白字约 6.5:1，满足 FR-7.3。 */
    val AccentSolid = Color(0xFF1D4ED8)
    val Offline = Color(0xFF64748B)
    val Live = Color(0xFFC81E1E)

    val accentGradient = Brush.horizontalGradient(listOf(AccentStart, AccentEnd))
    val backgroundGradient = Brush.verticalGradient(listOf(BgTop, BgBottom))
}

private val LightScheme = lightColorScheme(
    primary = JishiColors.AccentStart,
    onPrimary = Color.White,
    secondary = JishiColors.AccentEnd,
    background = JishiColors.BgTop,
    onBackground = JishiColors.TextPrimary,
    surface = JishiColors.Card,
    onSurface = JishiColors.TextPrimary,
    outline = JishiColors.Divider,
    outlineVariant = JishiColors.Divider,
    surfaceVariant = Color(0xFFF2F5FA),
    onSurfaceVariant = JishiColors.TextSecondary,
    surfaceContainerLow = JishiColors.Card,
    surfaceContainerHigh = JishiColors.Card,
    surfaceContainerHighest = Color(0xFFF2F5FA),
    primaryContainer = Color(0xFFE8F0FE),
    onPrimaryContainer = JishiColors.AccentSolid,
    secondaryContainer = Color(0xFFEAF7FB),
    onSecondaryContainer = JishiColors.TextPrimary,
    error = JishiColors.Live,
    onError = Color.White,
    errorContainer = Color(0xFFFFF3F3),
    onErrorContainer = JishiColors.Live,
    scrim = Color(0xBF000000),
)

/** 字号下限（FR-7.1）：正文 ≥12sp，关键信息 ≥14sp，页面标题 21sp。 */
val JishiTypography = Typography(
    headlineMedium = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = JishiColors.TextPrimary),
    titleMedium = TextStyle(fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, color = JishiColors.TextPrimary),
    bodyLarge = TextStyle(fontSize = 14.sp, color = JishiColors.TextPrimary),
    bodyMedium = TextStyle(fontSize = 13.5.sp, color = JishiColors.TextPrimary),
    bodySmall = TextStyle(fontSize = 12.sp, color = JishiColors.TextSecondary),
    labelSmall = TextStyle(fontSize = 12.sp, color = JishiColors.TextSecondary),
)

@Composable
fun JishiTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme 有意忽略：产品明确不做深色模式
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = LightScheme, typography = JishiTypography, content = content)
}
