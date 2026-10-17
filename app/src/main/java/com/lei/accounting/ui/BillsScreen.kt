package com.lei.accounting.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.lei.accounting.core.summary.SummaryCalculator
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val PeriodLabels = mapOf(Period.DAY to "今天", Period.WEEK to "本周", Period.MONTH to "本月")

@Composable
fun BillsScreen(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "switcher") {
            PillSwitcher(Period.entries, period, { PeriodLabels.getValue(it) }, vm::setPeriod, Modifier.fillMaxWidth())
        }
        item(key = "hero") { HeroCard(vm, data) }

        val d = data ?: return@LazyColumn
        if (d.bills.isEmpty()) {
            item(key = "empty") {
                EmptyState("🧾", "这段时间还没有账单", "用微信或支付宝付一笔会自动记上，\n也可以点右下角「记一笔」手动添加")
            }
            return@LazyColumn
        }
        val grouped = d.bills.groupBy { SummaryCalculator.localDateTime(it.time).toLocalDate() }
        grouped.forEach { (date, list) ->
            item(key = "day$date") { DayGroup(date, list, onClick = vm::edit) }
        }
    }
}

/** 顶部总览：渐变卡片 + 大号支出金额 */
@Composable
private fun HeroCard(vm: MainViewModel, data: PeriodData?) {
    val lei = LocalLeiColors.current
    val s = data?.summary
    val white = Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(lei.heroStart, lei.heroEnd)))
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Column {
            DateNavigator(
                title = data?.range?.title.orEmpty(),
                canForward = vm.canGoForward,
                onPrev = { vm.shift(false) },
                onNext = { vm.shift(true) },
                tint = white,
            )
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("支出", style = MaterialTheme.typography.labelLarge, color = white.copy(alpha = 0.8f))
                Text(
                    yuan(s?.totalExpense ?: 0),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = white,
                )
                s?.expenseChangePct?.let { pct ->
                    val unit = when (s.range.period) { Period.DAY -> "前一天"; Period.WEEK -> "上周"; Period.MONTH -> "上月" }
                    Surface(shape = CircleShape, color = white.copy(alpha = 0.18f), modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "比$unit " + (if (pct >= 0) "↑ $pct%" else "↓ ${-pct}%"),
                            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = white,
                        )
                    }
                }
                Spacer(Modifier.size(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    HeroStat("收入", yuan(s?.totalIncome ?: 0), Modifier.weight(1f))
                    HeroStat("结余", yuan((s?.totalIncome ?: 0) - (s?.totalExpense ?: 0)), Modifier.weight(1f))
                    HeroStat("笔数", "${s?.count ?: 0}", Modifier.weight(0.6f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("M月d日 E")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun DayGroup(date: LocalDate, bills: List<BillEntity>, onClick: (BillEntity) -> Unit) {
    val dayExpense = bills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountCents }
    val dayIncome = bills.filter { it.billType == BillType.INCOME }.sumOf { it.amountCents }
    val today = LocalDate.now()
    val title = when (date) {
        today -> "今天 · " + date.format(dayFmt)
        today.minusDays(1) -> "昨天 · " + date.format(dayFmt)
        else -> date.format(dayFmt)
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                buildString {
                    append("支出 ${yuan(dayExpense)}")
                    if (dayIncome > 0) append("  收入 ${yuan(dayIncome)}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            shape = RoundedCornerShape(20.dp),
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            yuan(bill.amountCents, sign = true, positive = !expense),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (expense) MaterialTheme.colorScheme.onSurface else lei.income,
        )
    }
}
