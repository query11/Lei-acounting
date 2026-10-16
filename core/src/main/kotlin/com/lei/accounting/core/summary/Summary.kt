package com.lei.accounting.core.summary

import com.lei.accounting.core.model.BillRecord
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.formatYuan
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String) {
    DAY("日"),
    WEEK("周"),
    MONTH("月"),
}

/** 半开区间 [startMillis, endMillis) */
data class DateRange(val period: Period, val start: LocalDate, val endExclusive: LocalDate, val zone: ZoneId) {
    val startMillis: Long get() = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis: Long get() = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()

    fun contains(millis: Long) = millis in startMillis until endMillis

    fun previous(): DateRange = of(period, when (period) {
        Period.DAY -> start.minusDays(1)
        Period.WEEK -> start.minusWeeks(1)
        Period.MONTH -> start.minusMonths(1)
    }, zone)

    fun next(): DateRange = of(period, endExclusive, zone)

    /** 用作缓存 key，例如 DAY:2026-10-16 */
    val key: String get() = "${period.name}:$start"

    val title: String
        get() = when (period) {
            Period.DAY -> "${start.monthValue}月${start.dayOfMonth}日"
            Period.WEEK -> {
                val last = endExclusive.minusDays(1)
                "${start.monthValue}.${start.dayOfMonth} - ${last.monthValue}.${last.dayOfMonth}"
            }
            Period.MONTH -> "${start.year}年${start.monthValue}月"
        }

    companion object {
        fun of(period: Period, anchor: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DateRange = when (period) {
            Period.DAY -> DateRange(period, anchor, anchor.plusDays(1), zone)
            Period.WEEK -> {
                val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                DateRange(period, monday, monday.plusWeeks(1), zone)
            }
            Period.MONTH -> {
                val first = anchor.withDayOfMonth(1)
                DateRange(period, first, first.plusMonths(1), zone)
            }
        }
    }
}

data class CategoryStat(val category: String, val cents: Long, val count: Int, val ratio: Double)
data class MerchantStat(val merchant: String, val cents: Long, val count: Int)

data class Summary(
    val range: DateRange,
    val totalExpense: Long,
    val totalIncome: Long,
    val count: Int,
    val byCategory: List<CategoryStat>,
    val topMerchants: List<MerchantStat>,
    val largest: BillRecord?,
    val previousExpense: Long?,
    /** 每天的支出（月/周总结用），key 是日期 */
    val dailyExpense: Map<LocalDate, Long>,
) {
    /** 和上一个周期相比的支出变化百分比；上期为 0 时返回 null */
    val expenseChangePct: Int?
        get() = previousExpense?.takeIf { it > 0 }?.let { (((totalExpense - it) * 100.0) / it).toInt() }

    /** 不依赖 AI 的本地文字总结 */
    fun toPlainText(): String = buildString {
        append("${range.title}：支出 ¥${totalExpense.formatYuan()}")
        if (totalIncome > 0) append("，收入 ¥${totalIncome.formatYuan()}")
        append("，共 ${count} 笔。")
        expenseChangePct?.let { pct ->
            val unit = when (range.period) { Period.DAY -> "昨天"; Period.WEEK -> "上周"; Period.MONTH -> "上月" }
            append(if (pct >= 0) "比${unit}多 $pct%。" else "比${unit}少 ${-pct}%。")
        }
        byCategory.take(3).takeIf { it.isNotEmpty() }?.let { top ->
            append("花得最多：")
            append(top.joinToString("、") { "${it.category} ¥${it.cents.formatYuan()}" })
            append("。")
        }
    }
}

object SummaryCalculator {
    fun compute(range: DateRange, bills: List<BillRecord>, previousBills: List<BillRecord>? = null): Summary {
        val inRange = bills.filter { range.contains(it.timeMillis) }
        val expenses = inRange.filter { it.type == BillType.EXPENSE }
        val totalExpense = expenses.sumOf { it.amountCents }
        val byCategory = expenses.groupBy { it.category }
            .map { (cat, list) ->
                val sum = list.sumOf { it.amountCents }
                CategoryStat(cat, sum, list.size, if (totalExpense == 0L) 0.0 else sum.toDouble() / totalExpense)
            }
            .sortedByDescending { it.cents }
        val topMerchants = expenses.filter { it.merchant.isNotBlank() }
            .groupBy { it.merchant }
            .map { (m, list) -> MerchantStat(m, list.sumOf { it.amountCents }, list.size) }
            .sortedByDescending { it.cents }
            .take(5)
        val zone = range.zone
        val daily = expenses.groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.sumOf { it.amountCents } }
            .toSortedMap()
        val prevRange = range.previous()
        val prevExpense = previousBills
            ?.filter { prevRange.contains(it.timeMillis) && it.type == BillType.EXPENSE }
            ?.sumOf { it.amountCents }
        return Summary(
            range = range,
            totalExpense = totalExpense,
            totalIncome = inRange.filter { it.type == BillType.INCOME }.sumOf { it.amountCents },
            count = inRange.size,
            byCategory = byCategory,
            topMerchants = topMerchants,
            largest = expenses.maxByOrNull { it.amountCents },
            previousExpense = prevExpense,
            dailyExpense = daily,
        )
    }

    fun localDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
}
