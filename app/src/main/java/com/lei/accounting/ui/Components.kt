package com.lei.accounting.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lei.accounting.core.model.PayApp

/**
 * 胶囊形分段切换（今天 / 本周 / 本月、支出 / 收入）。
 * 选中的白色滑块用弹簧动画滑到目标位置，文字颜色同步过渡，切换时有轻微震动反馈。
 */
@Composable
fun <T> PillSwitcher(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val lei = LocalLeiColors.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    var widthPx by remember { mutableIntStateOf(0) }
    val itemWidthPx = if (options.isEmpty()) 0f else widthPx.toFloat() / options.size
    val offsetPx by animateFloatAsState(
        targetValue = itemWidthPx * index,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "pillOffset",
    )
    Box(
        modifier
            .clip(CircleShape)
            .background(lei.pillTrack)
            .padding(4.dp)
            .height(IntrinsicSize.Min)
            .onSizeChanged { widthPx = it.width },
    ) {
        if (widthPx > 0) {
            Box(
                Modifier
                    .graphicsLayer { translationX = offsetPx }
                    .width(with(density) { itemWidthPx.toDp() })
                    .fillMaxHeight()
                    .shadow(1.5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(lei.pillIndicator),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option == selected
                val textColor by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(220),
                    label = "pillText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(option)
                            }
                        }
                        .padding(vertical = 9.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                    )
                }
            }
        }
    }
}

/** ‹ 10月17日 › */
@Composable
fun DateNavigator(
    title: String,
    canForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一个", tint = tint)
        }
        AnimatedContent(
            targetState = title,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 40)) + slideInVertically(tween(260)) { it / 3 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 3 })
            },
            label = "dateTitle",
        ) { t ->
            Text(
                t,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(onClick = onNext, enabled = canForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一个",
                tint = if (canForward) tint else tint.copy(alpha = 0.3f),
            )
        }
    }
}

/** 圆形分类图标 */
@Composable
fun CategoryAvatar(category: String, size: Dp = 42.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(categoryEmoji(category), fontSize = (size.value * 0.48f).sp)
    }
}

/** 「● 微信」这样的小标签：颜色点 + 文字，颜色只是辅助识别 */
@Composable
fun AppTag(app: PayApp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(app.brandColor()),
        )
        Spacer(Modifier.width(4.dp))
        Text(app.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 带小标题的白色圆角卡片 */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null || action != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.orEmpty(),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                action?.invoke()
            }
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** 细进度条：单一颜色表示占比，底槽是浅灰；出现时从 0 长到目标长度 */
@Composable
fun RatioBar(ratio: Float, modifier: Modifier = Modifier) {
    val lei = LocalLeiColors.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(ratio) {
        progress.animateTo(ratio.coerceIn(0f, 1f), tween(650, easing = FastOutSlowInEasing))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(lei.track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.value)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(lei.chart),
        )
    }
}

/** 平板 / 折叠屏上限制内容宽度并居中，手机上铺满 */
fun Modifier.readableWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = 640.dp)

/** 状态小胶囊：文字 + 颜色，颜色不单独表意 */
@Composable
fun StatusChip(ok: Boolean, okText: String = "已开启", badText: String = "未开启") {
    val color = if (ok) LocalLeiColors.current.income else MaterialTheme.colorScheme.error
    Surface(shape = CircleShape, color = color.copy(alpha = 0.12f)) {
        Text(
            (if (ok) "✓ " else "! ") + (if (ok) okText else badText),
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** 空状态 */
@Composable
fun EmptyState(emoji: String, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 48.sp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun VerticalSpace(height: Dp) = Spacer(Modifier.height(height))
