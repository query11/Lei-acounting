@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lei.accounting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.Locale

/** 解析用户输入的金额：允许「12」「12.」「12.5」「¥1,200」 */
private fun parseAmountInput(text: String): Long? {
    val t = text.trim().replace("¥", "").replace("￥", "").replace(",", "").replace("，", "")
    val m = Regex("""^(\d+)(?:\.(\d{0,2}))?$""").matchEntire(t) ?: return null
    return AmountParser.toCents(m.groupValues[1], m.groupValues[2])
}

/** 新增 / 编辑账单的底部弹窗 */
@Composable
fun BillEditSheet(
    bill: BillEntity,
    onDismiss: () -> Unit,
    onSave: (BillEntity) -> Unit,
    onDelete: (BillEntity) -> Unit,
    onReclassify: (BillEntity) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        BillEditForm(bill, onSave, onDelete, onReclassify)
    }
}

/** 记账表单本体（底部弹窗里的内容），单独拆出来方便预览 */
@Composable
fun BillEditForm(
    bill: BillEntity,
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
    val amountCents = parseAmountInput(amountText)
    val canSave = amountCents != null && amountCents > 0

    run {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNew) "记一笔" else "编辑账单",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                PillSwitcher(
                    options = BillType.entries,
                    selected = type,
                    label = { it.label },
                    onSelect = { t ->
                        type = t
                        if (category !in Categories.of(t)) category = Categories.of(t).first()
                    },
                    modifier = Modifier.width(150.dp),
                )
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                prefix = { Text("¥", fontSize = 28.sp, fontWeight = FontWeight.SemiBold) },
                placeholder = { Text("0.00", fontSize = 28.sp) },
                textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                singleLine = true,
                isError = amountText.isNotBlank() && !canSave,
                supportingText = if (amountText.isNotBlank() && !canSave) {
                    { Text("请输入正确的金额，例如 12.50") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分类", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CategoryGrid(Categories.of(type), category) { category = it }
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("商户 / 对象") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注 / 用途") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!isNew) BillInfo(bill)

            Button(
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
                enabled = canSave,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("保存", style = MaterialTheme.typography.titleMedium) }

            if (!isNew) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (bill.payApp != PayApp.MANUAL) {
                        TextButton(onClick = { onReclassify(bill) }) { Text("让 AI 重新分类") }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    TextButton(onClick = { if (confirmDelete) onDelete(bill) else confirmDelete = true }) {
                        Text(if (confirmDelete) "再点一次确认删除" else "删除这笔", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 每行 4 个的分类选择格子 */
@Composable
private fun CategoryGrid(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val itemWidth = (maxWidth - gap * 3) / 4
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            categories.forEach { c ->
                val isSelected = c == selected
                Column(
                    Modifier
                        .width(itemWidth)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        )
                        .clickable { onSelect(c) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(categoryEmoji(c), fontSize = 22.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        c,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 自动记账的来源信息：时间、渠道、原文 */
@Composable
private fun BillInfo(bill: BillEntity) {
    val time = SummaryCalculator.localDateTime(bill.time).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE))
    val sources = BillMerger.decodeSources(bill.sources).joinToString(" + ") { it.label }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            AppTag(bill.payApp)
        }
        Text(
            "识别方式：$sources" + if (bill.payMethod.isNotBlank()) " · ${bill.payMethod}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (bill.aiStatus == AiStatus.SUSPECT) {
            Text(
                "DeepSeek 认为这条可能不是真实交易，请确认后保存或删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (bill.rawText.isNotBlank()) {
            Text(
                "原文：${bill.rawText.take(160)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
