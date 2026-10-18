package com.lei.accounting.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.Summary
import com.lei.accounting.core.summary.SummaryCalculator
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val PeriodLabels = mapOf(Period.DAY to "今天", Period.WEEK to "本周", Period.MONTH to "本月")

@Composable
fun BillsScreen(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    BillsContent(
        period = period,
        data = data,
        canForward = vm.canGoForward,
        onPeriod = vm::setPeriod,
        onShift = vm::shift,
        onBillClick = vm::edit,
    )
}

/** 账单页（无状态，方便预览和截图测试） */
@Composable
fun BillsContent(
    period: Period,
    data: PeriodData?,
    canForward: Boolean,
    onPeriod: (Period) -> Unit,
    onShift: (Boolean) -> Unit,
    onBillClick: (BillEntity) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().readableWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "switcher") {
            PillSwitcher(Period.entries, period, { PeriodLabels.getValue(it) }, onPeriod, Modifier.fillMaxWidth())
        }
        item(key = "hero") { HeroCard(data, canForward, onShift) }

        val d = data ?: return@LazyColumn
        if (d.bills.isEmpty()) {
            item(key = "empty-${d.range.key}") {
                EmptyState(
                    "🧾",
                    "这段时间还没有账单",
                    "用微信、支付宝或银行卡付一笔会自动记上，\n也可以点右下角「记一笔」手动添加",
                    Modifier.animateItem(),
                )
            }
            return@LazyColumn
        }
        val grouped = d.bills.groupBy { SummaryCalculator.localDateTime(it.time).toLocalDate() }
        grouped.forEach { (date, list) ->
            item(key = "day$date") {
                DayGroup(date, list, onBillClick, Modifier.animateItem())
            }
        }
    }
}

/** 顶部总览：渐变卡片 + 会「数」上去的大号支出金额 */
@Composable
private fun HeroCard(data: PeriodData?, canForward: Boolean, onShift: (Boolean) -> Unit) {
    val lei = LocalLeiColors.current
    val white = Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(lei.heroStart, lei.heroEnd))),
    ) {
        // 右上角两道淡淡的光圈，增加一点层次
        Canvas(Modifier.matchParentSize()) {
            drawCircle(white.copy(alpha = 0.07f), radius = size.width * 0.42f, center = Offset(size.width * 0.95f, size.height * 0.02f))
            drawCircle(white.copy(alpha = 0.05f), radius = size.width * 0.26f, center = Offset(size.width * 0.80f, size.height * 0.30f))
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            DateNavigator(
                title = data?.range?.title.orEmpty(),
                canForward = canForward,
                onPrev = { onShift(false) },
                onNext = { onShift(true) },
                tint = white,
            )
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("支出", style = MaterialTheme.typography.labelLarge, color = white.copy(alpha = 0.8f))
                        CountingAmount(data?.summary?.totalExpense ?: 0, color = white)
                        ChangeBadge(data?.summary)
                    }
                    val summary = data?.summary
                    if (summary != null && summary.range.period != Period.DAY) {
                        MiniTrend(summary, Modifier.padding(bottom = 6.dp))
                    }
                }
                Spacer(Modifier.size(18.dp))
                val s = data?.summary
                Row(Modifier.fillMaxWidth()) {
                    HeroStat("收入", yuan(s?.totalIncome ?: 0), Modifier.weight(1f))
                    HeroStat("结余", yuan((s?.totalIncome ?: 0) - (s?.totalExpense ?: 0)), Modifier.weight(1f))
                    HeroStat("笔数", "${s?.count ?: 0}", Modifier.weight(0.6f))
                }
            }
        }
    }
}

/**
 * 总览卡片右侧的迷你趋势：这段时间每天的支出，今天最亮。
 * 只是「一眼看形状」，具体数字在统计页。
 */
@Composable
private fun MiniTrend(s: Summary, modifier: Modifier = Modifier) {
    val days = generateSequence(s.range.start) { it.plusDays(1) }.takeWhile { it < s.range.endExclusive }.toList()
    val values = days.map { s.dailyExpense[it] ?: 0L }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val today = LocalDate.now()
    val grow = remember(s.range) { Animatable(0f) }
    LaunchedEffect(s.range) { grow.animateTo(1f, tween(700, delayMillis = 150, easing = FastOutSlowInEasing)) }
    val width = if (days.size > 10) 124.dp else 96.dp
    Canvas(modifier.size(width = width, height = 44.dp)) {
        val slot = size.width / days.size
        val gap = if (days.size > 10) 1.5.dp.toPx() else 4.dp.toPx()
        val barW = (slot - gap).coerceAtLeast(1.dp.toPx())
        val r = (barW / 2).coerceAtMost(2.dp.toPx())
        days.forEachIndexed { i, day ->
            val v = values[i]
            val h = if (v > 0) (v.toFloat() / max * size.height * grow.value).coerceAtLeast(3.dp.toPx()) else 2.dp.toPx()
            val alpha = when {
                day == today -> 0.95f
                day > today -> 0.12f
                v > 0 -> 0.42f
                else -> 0.18f
            }
            drawRoundRect(
                Color.White.copy(alpha = alpha),
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(r, r),
            )
        }
    }
}

/** 金额变化时从旧值滚动到新值 */
@Composable
private fun CountingAmount(cents: Long, color: Color) {
    val animated by animateIntAsState(
        targetValue = cents.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        animationSpec = tween(650),
        label = "amount",
    )
    Text(
        yuan(animated.toLong()),
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun ChangeBadge(s: Summary?) {
    val pct = s?.expenseChangePct ?: return
    val unit = when (s.range.period) { Period.DAY -> "前一天"; Period.WEEK -> "上周"; Period.MONTH -> "上月" }
    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.16f), modifier = Modifier.padding(top = 6.dp)) {
        Text(
            "比$unit " + (if (pct >= 0) "↑ $pct%" else "↓ ${-pct}%"),
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.72f))
        Spacer(Modifier.size(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

@Composable
private fun DayGroup(date: LocalDate, bills: List<BillEntity>, onClick: (BillEntity) -> Unit, modifier: Modifier = Modifier) {
    val dayExpense = bills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountCents }
    val dayIncome = bills.filter { it.billType == BillType.INCOME }.sumOf { it.amountCents }
    val today = LocalDate.now()
    val title = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> null
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                date.format(dayFmt),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    append("支出 ${yuan(dayExpense)}")
                    if (dayIncome > 0) append("  ·  收入 ${yuan(dayIncome)}")
                },
                style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            bills.forEachIndexed { i, bill ->
                BillRow(bill) { onClick(bill) }
                if (i < bills.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun BillRow(bill: BillEntity, onClick: () -> Unit) {
    val lei = LocalLeiColors.current
    val expense = bill.billType == BillType.EXPENSE
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(bill.category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bill.merchant.ifBlank { bill.category },
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bill.aiStatus == AiStatus.SUSPECT) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "可能不是真实交易",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(bill.category, SummaryCalculator.localDateTime(bill.time).format(timeFmt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                AppTag(bill.payApp)
            }
            val hint = when {
                bill.aiStatus == AiStatus.PENDING && bill.payApp != PayApp.MANUAL -> "AI 分类中…"
                else -> bill.note
            }
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            yuan(bill.amountCents, sign = true, positive = !expense),
            style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
            color = if (expense) MaterialTheme.colorScheme.onSurface else lei.income,
        )
    }
}
