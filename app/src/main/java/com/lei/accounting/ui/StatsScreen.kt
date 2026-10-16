package com.lei.accounting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.core.summary.Period

@Composable
fun StatsScreen(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val saved by vm.savedSummary.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PeriodHeader(vm, data)
        val d = data ?: return@Column
        val s = d.summary
        TotalsCard(s)

        // AI / 本地总结
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("总结", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.toPlainText(), style = MaterialTheme.typography.bodyMedium)
                val ai = saved?.aiText.orEmpty()
                if (ai.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("DeepSeek 点评", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(ai, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.generateSummary() }, enabled = !busy && s.count > 0) {
                        Text(if (ai.isBlank()) "让 DeepSeek 总结" else "重新生成")
                    }
                    if (busy) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
        }

        if (s.byCategory.isNotEmpty()) {
            SectionTitle("支出分类")
            s.byCategory.forEach { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.category, Modifier.weight(1f))
                        Text("¥${c.cents.formatYuan()} · ${(c.ratio * 100).toInt()}% · ${c.count}笔", style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                        progress = { c.ratio.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = ExpenseColor,
                    )
                }
            }
        }

        if (s.range.period != Period.DAY && s.dailyExpense.isNotEmpty()) {
            SectionTitle("每日支出")
            DailyBars(s.dailyExpense.values.toList(), s.dailyExpense.keys.map { it.dayOfMonth.toString() })
        }

        if (s.topMerchants.isNotEmpty()) {
            SectionTitle("花钱最多的商户")
            s.topMerchants.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(m.merchant, Modifier.weight(1f), maxLines = 1)
                    Text("¥${m.cents.formatYuan()} (${m.count}笔)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        s.largest?.let {
            SectionTitle("最大一笔")
            Text(
                "${it.category} ${it.merchant} ¥${it.amountCents.formatYuan()}",
                Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

/** 极简柱状图：不引入图表库，保持 APK 小巧 */
@Composable
private fun DailyBars(values: List<Long>, labels: List<String>) {
    val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight((v.toFloat() / max).coerceIn(0.02f, 1f))
                            .background(ExpenseColor.copy(alpha = 0.7f), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    )
                }
                Text(
                    if (values.size <= 10 || i % 5 == 0) labels[i] else "",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}
