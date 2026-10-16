package com.lei.accounting.core

import com.lei.accounting.core.ai.DeepSeekPrompts
import com.lei.accounting.core.dedup.BillMerger
import com.lei.accounting.core.model.BillRecord
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.rule.Categories
import com.lei.accounting.core.summary.DateRange
import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.SummaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MergerAndSummaryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val t0 = LocalDate.of(2026, 10, 16).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun record(id: Long, cents: Long, time: Long, src: CaptureSource, merchant: String = "", cat: String = "餐饮") =
        BillRecord(id, cents, BillType.EXPENSE, PayApp.WECHAT, setOf(src), time, cat, merchant)

    private fun parsed(cents: Long, time: Long, src: CaptureSource, merchant: String = "") =
        ParsedBill(cents, BillType.EXPENSE, PayApp.WECHAT, src, time, merchant)

    @Test fun pageThenNotificationMerges() {
        val old = record(1, 990, t0, CaptureSource.PAGE, "瑞幸咖啡")
        val dup = BillMerger.findDuplicate(parsed(990, t0 + 20_000, CaptureSource.NOTIFICATION), listOf(old))
        assertNotNull(dup)
        val merged = BillMerger.merge(dup!!, parsed(990, t0 + 20_000, CaptureSource.NOTIFICATION))
        assertEquals(setOf(CaptureSource.PAGE, CaptureSource.NOTIFICATION), merged.sources)
        assertEquals("瑞幸咖啡", merged.merchant)
    }

    @Test fun twoRealPaymentsSameAmountAreKept() {
        // 两次刷公交 ¥2，相隔 10 分钟，同一渠道 → 两笔
        val old = record(1, 200, t0, CaptureSource.NOTIFICATION)
        assertNull(BillMerger.findDuplicate(parsed(200, t0 + 10 * 60_000, CaptureSource.NOTIFICATION), listOf(old)))
    }

    @Test fun differentMerchantNotMerged() {
        val old = record(1, 990, t0, CaptureSource.PAGE, "瑞幸咖啡")
        assertNull(BillMerger.findDuplicate(parsed(990, t0 + 1000, CaptureSource.PAGE, "星巴克"), listOf(old)))
    }

    @Test fun categoryGuess() {
        assertEquals("交通", Categories.guess(BillType.EXPENSE, "滴滴出行"))
        assertEquals("餐饮", Categories.guess(BillType.EXPENSE, "瑞幸咖啡"))
        assertEquals("日用", Categories.guess(BillType.EXPENSE, "美宜佳便利店"))
        assertEquals("退款", Categories.guess(BillType.INCOME, "退款到账"))
        assertEquals("其他", Categories.guess(BillType.EXPENSE, "某某有限公司"))
    }

    @Test fun weeklySummary() {
        val range = DateRange.of(Period.WEEK, LocalDate.of(2026, 10, 16), zone) // 周五
        assertEquals(LocalDate.of(2026, 10, 12), range.start)
        val bills = listOf(
            record(1, 1000, t0, CaptureSource.PAGE, "瑞幸咖啡"),
            record(2, 3000, t0 - 86_400_000, CaptureSource.PAGE, "滴滴出行", "交通"),
            record(3, 5000, t0 - 7 * 86_400_000, CaptureSource.PAGE, "上周的", "购物"),
        )
        val s = SummaryCalculator.compute(range, bills, bills)
        assertEquals(4000L, s.totalExpense)
        assertEquals("交通", s.byCategory.first().category)
        assertEquals(5000L, s.previousExpense)
        assertEquals(-20, s.expenseChangePct)
        assertFalse(s.toPlainText().isBlank())
    }

    @Test fun parseAiJson() {
        val r = DeepSeekPrompts.parseClassification(
            "```json\n{\"isBill\":true,\"category\":\"餐饮\",\"merchant\":\"瑞幸\",\"note\":\"早上咖啡\"}\n```",
            BillType.EXPENSE,
        )!!
        assertEquals("餐饮", r.category)
        assertEquals("瑞幸", r.merchant)
        val bad = DeepSeekPrompts.parseClassification("{\"category\":\"乱写的\"}", BillType.EXPENSE)!!
        assertEquals("其他", bad.category)
        assertNull(DeepSeekPrompts.parseClassification("not json", BillType.EXPENSE))
    }
}
