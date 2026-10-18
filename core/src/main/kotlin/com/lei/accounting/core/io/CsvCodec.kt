package com.lei.accounting.core.io

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.parser.AmountParser
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 导入时解析出的一行账单 */
data class ImportedBill(
    val timeMillis: Long,
    val type: BillType,
    val amountCents: Long,
    val category: String,
    val merchant: String,
    val note: String,
    val app: PayApp,
    val payMethod: String,
)

/**
 * 账单 CSV 的读写。格式与「导出全部账单」一致：
 * 时间,收支,金额,分类,商户,备注,来源,支付方式
 */
object CsvCodec {
    val HEADER = listOf("时间", "收支", "金额", "分类", "商户", "备注", "来源", "支付方式")
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun encodeRow(cells: List<String>): String = cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }

    data class Result(val bills: List<ImportedBill>, val badLines: Int)

    fun decode(text: String, zone: ZoneId = ZoneId.systemDefault()): Result {
        val rows = splitRows(text.removePrefix("﻿"))
        val bills = mutableListOf<ImportedBill>()
        var bad = 0
        for (row in rows) {
            if (row.all { it.isBlank() }) continue
            if (row.firstOrNull()?.trim() == HEADER.first()) continue
            val b = parseRow(row, zone)
            if (b == null) bad++ else bills += b
        }
        return Result(bills, bad)
    }

    private fun parseRow(c: List<String>, zone: ZoneId): ImportedBill? {
        if (c.size < 3) return null
        val time = runCatching { LocalDateTime.parse(c[0].trim(), timeFmt) }.getOrNull() ?: return null
        val amountText = c[2].trim()
        val negative = amountText.startsWith("-")
        val m = Regex("""^[-+]?\s*[¥￥]?\s*([\d,]+)(?:\.(\d{1,2}))?$""").matchEntire(amountText) ?: return null
        val cents = AmountParser.toCents(m.groupValues[1], m.groupValues[2]) ?: return null
        if (cents <= 0) return null
        val type = when (c[1].trim()) {
            "支出" -> BillType.EXPENSE
            "收入" -> BillType.INCOME
            else -> if (negative) BillType.EXPENSE else BillType.INCOME
        }
        val appLabel = c.getOrNull(6)?.trim().orEmpty()
        return ImportedBill(
            timeMillis = time.atZone(zone).toInstant().toEpochMilli(),
            type = type,
            amountCents = cents,
            category = c.getOrNull(3)?.trim().orEmpty(),
            merchant = c.getOrNull(4)?.trim().orEmpty(),
            note = c.getOrNull(5)?.trim().orEmpty(),
            app = PayApp.entries.firstOrNull { it.label == appLabel } ?: PayApp.MANUAL,
            payMethod = c.getOrNull(7)?.trim().orEmpty(),
        )
    }

    /** 标准 CSV 拆分：支持引号、引号内的逗号和换行、"" 转义 */
    fun splitRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes && ch == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch == ',' -> { row += cell.toString(); cell.clear() }
                !inQuotes && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    rows += row; row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }
}
