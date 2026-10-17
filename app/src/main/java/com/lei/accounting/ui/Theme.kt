package com.lei.accounting.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.model.formatYuan
import kotlin.math.abs

/**
 * 固定的品牌配色（不跟随系统取色，Flyme 的动态取色会让界面发灰发淡）。
 * 图表蓝取自已校验过色盲安全与对比度的色阶：浅色 #2A78D6、深色 #3987E5。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A78D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FA),
    onPrimaryContainer = Color(0xFF0D366B),
    secondary = Color(0xFF52514E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDEA),
    onSecondaryContainer = Color(0xFF1F1F1D),
    tertiary = Color(0xFF1BAF7A),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFF4F5F7),
    onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFECEDEF),
    onSurfaceVariant = Color(0xFF5F5E5A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF0F1F3),
    surfaceContainerHigh = Color(0xFFE9EAED),
    surfaceContainerHighest = Color(0xFFE3E4E8),
    outline = Color(0xFFC3C2B7),
    outlineVariant = Color(0xFFE8E8E3),
    error = Color(0xFFD03B3B),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3987E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF184F95),
    onPrimaryContainer = Color(0xFFCDE2FB),
    secondary = Color(0xFFC3C2B7),
    onSecondary = Color(0xFF1A1A19),
    secondaryContainer = Color(0xFF2C2C2A),
    onSecondaryContainer = Color(0xFFF0EFEC),
    tertiary = Color(0xFF199E70),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF232322),
    onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainerLowest = Color(0xFF1A1A19),
    surfaceContainerLow = Color(0xFF1E1E1D),
    surfaceContainer = Color(0xFF242423),
    surfaceContainerHigh = Color(0xFF2B2B29),
    surfaceContainerHighest = Color(0xFF323230),
    outline = Color(0xFF4A4A46),
    outlineVariant = Color(0xFF2C2C2A),
    error = Color(0xFFE66767),
    onError = Color(0xFF1A1A19),
)

/** Material 配色之外的语义色 */
@Immutable
data class LeiColors(
    /** 收入金额（带「+」号，颜色只是辅助） */
    val income: Color,
    /** 顶部总览卡片的渐变 */
    val heroStart: Color,
    val heroEnd: Color,
    /** 图表：选中的柱子 / 其余柱子（同一色相的浅一阶） */
    val chart: Color,
    val chartMuted: Color,
    /** 进度条底槽 */
    val track: Color,
)

private val LightLei = LeiColors(
    income = Color(0xFF006300),
    heroStart = Color(0xFF2A78D6),
    heroEnd = Color(0xFF1C5CAB),
    chart = Color(0xFF2A78D6),
    chartMuted = Color(0xFF86B6EF),
    track = Color(0xFFECEDEF),
)

private val DarkLei = LeiColors(
    income = Color(0xFF0CA30C),
    heroStart = Color(0xFF1C5CAB),
    heroEnd = Color(0xFF104281),
    chart = Color(0xFF3987E5),
    chartMuted = Color(0xFF184F95),
    track = Color(0xFF2C2C2A),
)

val LocalLeiColors = staticCompositionLocalOf { LightLei }

private val LeiShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun LeiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalLeiColors provides if (dark) DarkLei else LightLei) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = LeiShapes,
            content = content,
        )
    }
}

/** 分类图标 */
fun categoryEmoji(category: String): String = when (category) {
    "餐饮" -> "🍜"
    "交通" -> "🚇"
    "购物" -> "🛍️"
    "日用" -> "🧴"
    "娱乐" -> "🎮"
    "住房" -> "🏠"
    "通讯" -> "📱"
    "医疗" -> "💊"
    "教育" -> "📚"
    "人情" -> "🧧"
    "转账" -> "💸"
    "工资" -> "💼"
    "红包" -> "🧧"
    "退款" -> "↩️"
    "理财" -> "📈"
    "其他收入" -> "💰"
    else -> "📦"
}

/** 付款 App 的品牌色（只用于旁边带文字标签的小圆点） */
fun PayApp.brandColor(): Color = when (this) {
    PayApp.WECHAT -> Color(0xFF07C160)
    PayApp.ALIPAY -> Color(0xFF1677FF)
    PayApp.MANUAL -> Color(0xFF898781)
}

/** ¥1,234.50；sign=true 时带 +/- 号 */
fun yuan(cents: Long, sign: Boolean = false, positive: Boolean = true): String {
    val raw = abs(cents).formatYuan()
    val intPart = raw.substringBefore('.')
    val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
    val prefix = when {
        !sign -> if (cents < 0) "-" else ""
        positive -> "+"
        else -> "-"
    }
    return "$prefix¥$grouped.${raw.substringAfter('.')}"
}
