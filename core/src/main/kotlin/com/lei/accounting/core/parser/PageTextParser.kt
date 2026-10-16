package com.lei.accounting.core.parser

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp

/**
 * 解析「支付成功」页面上的文字（来自无障碍节点或 OCR）。
 *
 * 只在页面上同时存在 ①单独的成功标题（支付成功/付款成功/转账成功…）和 ②「完成」按钮 时才认为是刚付完款，
 * 这样聊天记录里出现的「支付成功」、账单详情页等都不会被重复记账。
 */
object PageTextParser {

    private val expenseTitles = setOf("支付成功", "付款成功", "转账成功", "已支付", "支付完成", "付款完成", "交易成功", "充值成功", "缴费成功")
    private val incomeTitles = setOf("收款成功", "已收款", "已收钱", "退款成功")
    private val doneButtons = setOf("完成", "返回商家", "完成并返回")

    /** 这些文字不可能是商户名 */
    private val notMerchant = Regex(
        "^(支付成功|付款成功|转账成功|已支付|支付完成|付款完成|交易成功|收款成功|完成|返回|关闭|确定|取消|更多|分享|" +
            "支付方式|付款方式|收款方|商户|商家|商品|订单号|交易单号|当前状态|查看账单|账单详情|查看详情|" +
            "零钱|零钱通|余额|余额宝|花呗|信用卡|储蓄卡|银行卡|优惠|立减|已优惠|积分|广告|推荐|为你推荐|" +
            "¥|￥|元|人民币|微信支付|支付宝|备注|.*\\d{6,}.*)$",
    )
    private val labelRe = Regex("^(收款方|商户名称|商户|商家|收款人|对方账户|商品|商品说明)[:：]?\\s*(.*)$")
    private val payMethodLabelRe = Regex("^(支付方式|付款方式)[:：]?\\s*(.*)$")

    /** 快速判断：这一屏是不是刚支付完成的页面 */
    fun looksLikeSuccessPage(texts: List<String>): Boolean {
        val set = texts.map { it.trim() }.toSet()
        val hasTitle = set.any { it in expenseTitles || it in incomeTitles }
        val hasDone = set.any { it in doneButtons }
        return hasTitle && hasDone
    }

    fun parse(app: PayApp, rawTexts: List<String>, timeMillis: Long, source: CaptureSource = CaptureSource.PAGE): ParsedBill? {
        val texts = mergeSplitAmounts(rawTexts.map { it.trim() }.filter { it.isNotEmpty() })
        if (!looksLikeSuccessPage(texts)) return null
        val titleIdx = texts.indexOfFirst { it in expenseTitles || it in incomeTitles }
        val type = if (texts[titleIdx] in incomeTitles) BillType.INCOME else BillType.EXPENSE

        // 金额：优先取成功标题之后的第一个独立金额
        val amountIdx = (titleIdx until texts.size).firstOrNull { AmountParser.parseStandalone(texts[it]) != null }
            ?: texts.indices.firstOrNull { AmountParser.parseStandalone(texts[it]) != null }
        val amount = amountIdx?.let { AmountParser.parseStandalone(texts[it]) }
            ?: AmountParser.parse(texts.joinToString(" "))
            ?: return null

        var merchant = ""
        var payMethod = ""
        for ((i, s) in texts.withIndex()) {
            labelRe.matchEntire(s)?.let { m ->
                val v = m.groupValues[2].ifBlank { texts.getOrNull(i + 1).orEmpty() }
                if (merchant.isEmpty() && isMerchantLike(v)) merchant = v
            }
            payMethodLabelRe.matchEntire(s)?.let { m ->
                val v = m.groupValues[2].ifBlank { texts.getOrNull(i + 1).orEmpty() }
                if (payMethod.isEmpty() && AmountParser.parseStandalone(v) == null) payMethod = v
            }
        }
        if (merchant.isEmpty() && amountIdx != null) {
            // 微信/支付宝的成功页一般是：标题 → 商户名 → 金额，或 标题 → 金额 → 商户名
            val before = (amountIdx - 1 downTo titleIdx + 1).firstOrNull { isMerchantLike(texts[it]) }
            val after = (amountIdx + 1 until minOf(texts.size, amountIdx + 3)).firstOrNull { isMerchantLike(texts[it]) }
            merchant = (before ?: after)?.let { texts[it] }.orEmpty()
        }

        return ParsedBill(
            amountCents = amount,
            type = type,
            app = app,
            source = source,
            timeMillis = timeMillis,
            merchant = merchant,
            payMethod = payMethod,
            rawText = texts.joinToString(" ").take(500),
        )
    }

    private fun isMerchantLike(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..40) return false
        if (notMerchant.matches(t)) return false
        if (AmountParser.parseStandalone(t) != null) return false
        if (t.all { it.isDigit() || it in ".:-/ " }) return false // 时间、日期
        return t.any { it.isLetter() }
    }

    /** 有的页面把「¥」和「42.10」拆成两个节点，这里把它们拼回去 */
    private fun mergeSplitAmounts(texts: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < texts.size) {
            val cur = texts[i]
            val next = texts.getOrNull(i + 1)
            if ((cur == "¥" || cur == "￥" || cur == "-¥" || cur == "-￥") && next != null && next.matches(Regex("""[\d,]+(\.\d{1,2})?"""))) {
                out += "¥$next"
                i += 2
            } else {
                out += cur
                i++
            }
        }
        return out
    }
}
