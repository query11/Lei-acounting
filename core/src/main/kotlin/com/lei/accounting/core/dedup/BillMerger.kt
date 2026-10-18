package com.lei.accounting.core.dedup

import com.lei.accounting.core.model.BillRecord
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp
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
            .filter { it.amountCents == new.amountCents && it.type == new.type && appsCompatible(it.app, new.app) }
            .filter { old ->
                val dt = abs(old.timeMillis - new.timeMillis)
                // 同一个 App 的同一种渠道重复上报才用短窗口；银行短信可能比微信通知晚到一两分钟
                val sameChannel = new.source in old.sources && old.app == new.app
                if (sameChannel) dt <= SAME_SOURCE_WINDOW_MS else dt <= CROSS_SOURCE_WINDOW_MS
            }
            .filter { old -> merchantsCompatible(old.merchant, new.merchant) }
            .minByOrNull { abs(it.timeMillis - new.timeMillis) }

    /** 把新捕获到的信息补进旧记录（只补空缺字段，不覆盖已有内容） */
    fun merge(old: BillRecord, new: ParsedBill): BillRecord = old.copy(
        sources = old.sources + new.source,
        merchant = old.merchant.ifBlank { new.merchant },
        payMethod = old.payMethod.ifBlank { new.payMethod },
        // 银行通知先到、微信/支付宝的记录后到时，以微信/支付宝为准
        app = if (old.app == PayApp.BANK && new.app != PayApp.BANK) new.app else old.app,
        note = old.note.ifBlank { new.note },
        // 时间以最早捕获的为准
        timeMillis = minOf(old.timeMillis, new.timeMillis),
    )

    /**
     * 用银行卡在微信/支付宝里付款时，会同时收到微信/支付宝的记录和银行的动账通知，这是同一笔钱。
     * 所以「银行卡」可以和任何渠道合并；微信和支付宝之间不合并。
     */
    private fun appsCompatible(a: PayApp, b: PayApp): Boolean = a == b || a == PayApp.BANK || b == PayApp.BANK

    private fun merchantsCompatible(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return true
        return a.contains(b) || b.contains(a)
    }

    fun encodeSources(sources: Set<CaptureSource>): String = sources.joinToString(",") { it.name }

    fun decodeSources(s: String): Set<CaptureSource> =
        s.split(',').mapNotNull { name -> CaptureSource.entries.firstOrNull { it.name == name.trim() } }.toSet()
}
