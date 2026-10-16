package com.lei.accounting.core.dedup

import com.lei.accounting.core.model.BillRecord
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import kotlin.math.abs

/**
 * 同一笔支付往往会被捕获多次：支付成功页（无障碍）+ 微信支付通知 + 页面重绘。
 * 这里判断新捕获的账单是否和最近入库的某条账单是同一笔。
 */
object BillMerger {
    /** 不同渠道（通知 vs 页面）之间，相隔多久以内算同一笔 */
    const val CROSS_SOURCE_WINDOW_MS = 5 * 60_000L

    /** 同一渠道重复上报（通知更新、页面重绘）的窗口；超过这个时间的同额账单视为新的一笔 */
    const val SAME_SOURCE_WINDOW_MS = 90_000L

    fun findDuplicate(new: ParsedBill, recent: List<BillRecord>): BillRecord? =
        recent
            .filter { it.amountCents == new.amountCents && it.type == new.type && it.app == new.app }
            .filter { old ->
                val dt = abs(old.timeMillis - new.timeMillis)
                if (new.source in old.sources) dt <= SAME_SOURCE_WINDOW_MS else dt <= CROSS_SOURCE_WINDOW_MS
            }
            .filter { old -> merchantsCompatible(old.merchant, new.merchant) }
            .minByOrNull { abs(it.timeMillis - new.timeMillis) }

    /** 把新捕获到的信息补进旧记录（只补空缺字段，不覆盖已有内容） */
    fun merge(old: BillRecord, new: ParsedBill): BillRecord = old.copy(
        sources = old.sources + new.source,
        merchant = old.merchant.ifBlank { new.merchant },
        payMethod = old.payMethod.ifBlank { new.payMethod },
        note = old.note.ifBlank { new.note },
        // 时间以最早捕获的为准
        timeMillis = minOf(old.timeMillis, new.timeMillis),
    )

    private fun merchantsCompatible(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return true
        return a.contains(b) || b.contains(a)
    }

    fun encodeSources(sources: Set<CaptureSource>): String = sources.joinToString(",") { it.name }

    fun decodeSources(s: String): Set<CaptureSource> =
        s.split(',').mapNotNull { name -> CaptureSource.entries.firstOrNull { it.name == name.trim() } }.toSet()
}
