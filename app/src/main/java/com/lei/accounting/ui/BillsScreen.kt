@file:OptIn(ExperimentalMaterial3Api::class)

package com.lei.accounting.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.Summary
import com.lei.accounting.core.summary.SummaryCalculator
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import java.time.format.DateTimeFormatter

val ExpenseColor = Color(0xFFD84315)
val IncomeColor = Color(0xFF2E7D32)

@Composable
fun PeriodHeader(vm: MainViewModel, data: PeriodData?) {
    val period by vm.period.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == period,
                    onClick = { vm.setPeriod(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                ) { Text(listOf("今天", "本周", "本月")[i]) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.shift(false) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一个")
            }
            Text(
                data?.range?.title.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { vm.shift(true) }, enabled = vm.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一个")
            }
        }
    }
}

@Composable
fun TotalsCard(s: Summary) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("支出", style = MaterialTheme.typography.labelMedium)
                Text("¥${s.totalExpense.formatYuan()}", style = MaterialTheme.typography.headlineSmall, color = ExpenseColor, fontWeight = FontWeight.Bold)
                s.expenseChangePct?.let { pct ->
                    Text(
                        if (pct >= 0) "比上期 +$pct%" else "比上期 $pct%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("收入", style = MaterialTheme.typography.labelMedium)
                Text("¥${s.totalIncome.formatYuan()}", style = MaterialTheme.typography.headlineSmall, color = IncomeColor, fontWeight = FontWeight.Bold)
                Text("共 ${s.count} 笔", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun BillsScreen(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        PeriodHeader(vm, data)
        val d = data ?: return@Column
        TotalsCard(d.summary)
        if (d.bills.isEmpty()) {
            Text(
                "这段时间还没有账单。\n用微信或支付宝付一笔，或者点右下角「+」手动记一笔。",
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val dayFmt = DateTimeFormatter.ofPattern("M月d日 E")
        val grouped = d.bills.groupBy { SummaryCalculator.localDateTime(it.time).toLocalDate() }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            grouped.forEach { (date, list) ->
                item(key = "h$date") {
                    val dayExpense = list.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountCents }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(date.format(dayFmt), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text("支出 ¥${dayExpense.formatYuan()}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                items(list, key = { it.id }) { bill ->
                    BillRow(bill) { vm.edit(bill) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
}

@Composable
private fun BillRow(bill: BillEntity, onClick: () -> Unit) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bill.category, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (bill.merchant.isNotBlank()) {
                    Text(
                        " · ${bill.merchant}",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (bill.aiStatus == AiStatus.SUSPECT) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Warning, contentDescription = "可能不是真实交易", tint = MaterialTheme.colorScheme.error)
                }
            }
            val meta = listOf(
                SummaryCalculator.localDateTime(bill.time).format(timeFmt),
                bill.payApp.label,
                bill.note,
                if (bill.aiStatus == AiStatus.PENDING && bill.payApp.name != "MANUAL") "待AI分类" else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        val expense = bill.billType == BillType.EXPENSE
        Text(
            (if (expense) "-" else "+") + bill.amountCents.formatYuan(),
            style = MaterialTheme.typography.titleMedium,
            color = if (expense) ExpenseColor else IncomeColor,
        )
    }
}
