@file:OptIn(ExperimentalLayoutApi::class)

package com.lei.accounting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lei.accounting.core.dedup.BillMerger
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.core.parser.AmountParser
import com.lei.accounting.core.rule.Categories
import com.lei.accounting.core.summary.SummaryCalculator
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import java.time.format.DateTimeFormatter

@Composable
fun BillEditDialog(
    bill: BillEntity,
    onDismiss: () -> Unit,
    onSave: (BillEntity) -> Unit,
    onDelete: (BillEntity) -> Unit,
    onReclassify: (BillEntity) -> Unit,
) {
    val isNew = bill.id == 0L
    var amountText by remember(bill.id) { mutableStateOf(if (bill.amountCents > 0) bill.amountCents.formatYuan() else "") }
    var type by remember(bill.id) { mutableStateOf(bill.billType) }
    var category by remember(bill.id) { mutableStateOf(bill.category) }
    var merchant by remember(bill.id) { mutableStateOf(bill.merchant) }
    var note by remember(bill.id) { mutableStateOf(bill.note) }
    var confirmDelete by remember(bill.id) { mutableStateOf(false) }

    val amountCents = amountText.trim().let { t ->
        Regex("""^(\d+)(?:\.(\d{1,2}))?$""").matchEntire(t)?.let { AmountParser.toCents(it.groupValues[1], it.groupValues[2]) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "记一笔" else "编辑账单") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BillType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = {
                                type = t
                                if (category !in Categories.of(t)) category = Categories.of(t).first()
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && amountCents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分类", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Categories.of(type).forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户 / 对象") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注 / 用途") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isNew) {
                    val time = SummaryCalculator.localDateTime(bill.time).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    val sources = BillMerger.decodeSources(bill.sources).joinToString("+") { it.label }
                    Text(
                        "$time · ${bill.payApp.label} · 识别方式：$sources" +
                            (if (bill.payMethod.isNotBlank()) " · ${bill.payMethod}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (bill.aiStatus == AiStatus.SUSPECT) {
                        Text("DeepSeek 认为这条可能不是真实交易，请确认后保存或删除。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (bill.rawText.isNotBlank()) {
                        Text("原文：${bill.rawText.take(200)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        if (bill.payApp != PayApp.MANUAL) {
                            TextButton(onClick = { onReclassify(bill) }) { Text("AI 重新分类") }
                        }
                        TextButton(onClick = { if (confirmDelete) onDelete(bill) else confirmDelete = true }) {
                            Text(if (confirmDelete) "确认删除？" else "删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = amountCents != null && amountCents > 0,
                onClick = {
                    onSave(
                        bill.copy(
                            amountCents = amountCents ?: 0,
                            type = type.name,
                            category = category,
                            merchant = merchant.trim(),
                            note = note.trim(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
