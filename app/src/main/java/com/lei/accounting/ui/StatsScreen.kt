package com.lei.accounting.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.core.summary.DateRange
import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.Summary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.temporal.ChronoUnit

@Composable
fun StatsScreen(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val saved by vm.savedSummary.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    StatsContent(
        period = period,
        data = data,
        aiText = saved?.aiText.orEmpty(),
        busy = busy,
        canForward = vm.canGoForward,
        onPeriod = vm::setPeriod,
        onShift = vm::shift,
        onGenerate = { vm.generateSummary() },
    )
}

/** 统计页（无状态，方便预览和截图测试） */
@Composable
fun StatsContent(
    period: Period,
    data: PeriodData?,
    aiText: String,
    busy: Boolean,
    canForward: Boolean,
    onPeriod: (Period) -> Unit,
    onShift: (Boolean) -> Unit,
    onGenerate: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().readableWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(0.dp))
        PillSwitcher(Period.entries, period, { PeriodLabels.getValue(it) }, onPeriod, Modifier.fillMaxWidth())
        DateNavigator(
            title = data?.range?.title.orEmpty(),
            canForward = canForward,
            onPrev = { onShift(false) },
            onNext = { onShift(true) },
        )
        val d = data ?: return@Column
        val s = d.summary

        KpiRow(s)

        if (s.range.period != Period.DAY) {
            SectionCard("每日支出") { DailyBarChart(s) }
        }

        AiSummaryCard(
            localText = s.toPlainText(),
            aiText = aiText,
            busy = busy,
            enabled = s.count > 0,
            onGenerate = onGenerate,
        )

        if (s.byCategory.isNotEmpty()) {
            SectionCard("支出分类") {
                s.byCategory.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(c.category, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.category, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(yuan(c.cents), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(6.dp))
                            RatioBar(c.ratio.toFloat())
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${(c.ratio * 100).toInt()}% · ${c.count} 笔",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (i < s.byCategory.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 62.dp, end = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        if (s.topMerchants.isNotEmpty()) {
            SectionCard("花钱最多的商户") {
                s.topMerchants.forEachIndexed { i, m ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(m.merchant, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${yuan(m.cents)} · ${m.count}笔",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 三个数字卡片：支出 / 收入 / 日均或笔数 */
@Composable
private fun KpiRow(s: Summary) {
    val days = ChronoUnit.DAYS.between(s.range.start, minOf(s.range.endExclusive, LocalDate.now().plusDays(1))).coerceAtLeast(1)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("支出", yuan(s.totalExpense), s.expenseChangePct?.let { if (it >= 0) "比上期 ↑$it%" else "比上期 ↓${-it}%" }, Modifier.weight(1f))
        StatTile("收入", yuan(s.totalIncome), null, Modifier.weight(1f))
        if (s.range.period == Period.DAY) {
            StatTile("笔数", "${s.count}", null, Modifier.weight(0.8f))
        } else {
            StatTile("日均支出", yuan(s.totalExpense / days), null, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String?, modifier: Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private val readoutFmt = DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)

/**
 * 每日支出柱状图（单一序列，一个颜色）。
 * 默认高亮花得最多的那天并直接标出金额；点任意一根柱子查看当天金额。
 */
@Composable
private fun DailyBarChart(s: Summary) {
    val lei = LocalLeiColors.current
    val baseline = MaterialTheme.colorScheme.outline
    val days = remember(s.range) { daysOf(s.range) }
    val values = days.map { s.dailyExpense[it] ?: 0L }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val defaultIdx = values.indices.maxByOrNull { values[it] }?.takeIf { values[it] > 0 } ?: -1
    var selected by remember(s.range, values) { mutableIntStateOf(defaultIdx) }
    // 换周期时柱子从基线长出来
    val grow = remember(s.range) { Animatable(0f) }
    LaunchedEffect(s.range) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected >= 0) days[selected].format(readoutFmt) else "点柱子查看当天金额",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected >= 0) {
                Text(yuan(values[selected]), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / values.size
                        selected = (offset.x / slot).toInt().coerceIn(0, values.lastIndex)
                    }
                },
        ) {
            val n = values.size
            val slot = size.width / n
            val gap = 2.dp.toPx()
            val barWidth = (slot - gap).coerceAtMost(28.dp.toPx())
            val radius = 4.dp.toPx().coerceAtMost(barWidth / 2)
            val chartHeight = size.height - 1.dp.toPx()
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = (v.toFloat() / max * chartHeight * grow.value).coerceAtLeast(2.dp.toPx())
                val left = i * slot + (slot - barWidth) / 2
                val top = chartHeight - h
                val color = if (i == selected) lei.chart else lei.chartMuted
                // 顶部圆角、底部贴着基线是直角
                drawRoundRect(color, Offset(left, top), Size(barWidth, h), CornerRadius(radius, radius))
                if (h > radius) drawRect(color, Offset(left, chartHeight - radius), Size(barWidth, radius))
            }
            drawLine(baseline, Offset(0f, chartHeight), Offset(size.width, chartHeight), strokeWidth = 1.dp.toPx())
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, day ->
                val label = when {
                    s.range.period == Period.WEEK -> weekdayNames[day.dayOfWeek.value - 1]
                    day.dayOfMonth == 1 || day.dayOfMonth % 5 == 0 -> "${day.dayOfMonth}"
                    else -> ""
                }
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private fun daysOf(range: DateRange): List<LocalDate> {
    val out = ArrayList<LocalDate>()
    var d = range.start
    while (d < range.endExclusive) {
        out += d
        d = d.plusDays(1)
    }
    return out
}

@Composable
private fun AiSummaryCard(localText: String, aiText: String, busy: Boolean, enabled: Boolean, onGenerate: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("✨ 总结", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(localText, style = MaterialTheme.typography.bodyMedium)
            if (aiText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(12.dp),
                ) {
                    Column {
                        Text(
                            "DeepSeek 点评",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(aiText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onGenerate, enabled = !busy && enabled) {
                    Text(if (aiText.isBlank()) "让 DeepSeek 总结" else "重新生成")
                }
                if (busy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
