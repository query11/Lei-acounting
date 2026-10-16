package com.lei.accounting.core.parser

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp

/**
 * 解析微信 / 支付宝的通知栏消息。
 *
 * 微信：只有「微信支付」「微信收款助手」等服务号推送才算账单，聊天消息一律忽略，
 *      常见文案：标题「微信支付」，内容「已支付¥42.10」「微信支付收款0.01元」。
 * 支付宝：通知没有固定的服务号，所以要求同时满足「有金额」+「有交易关键词」+「不像营销」。
 */
object NotificationParser {

    private val wechatPayTitles = setOf("微信支付", "微信收款助手", "微信收款商业版", "微信转账", "微信红包")

    /** 微信把多条消息合并时会变成「[3条]微信支付: 已支付¥1.00」 */
    private val wechatPrefix = Regex("""^(\[\d+条])?\s*(微信支付|微信收款助手|微信收款商业版)\s*[:：]\s*""")

    private val incomeWords = Regex("收款|到账|已存入|退款|收到|转入|入账|向你付款|转给你|转账给你|已收钱|返现")
    private val expenseWords = Regex("已支付|支付成功|付款成功|成功付款|已付款|消费|扣款|扣费|自动续费|已扣|向(?!你).{1,20}付款|转账给(?!你)|转给(?!你)|付款给(?!你)|支出")

    /** 典型营销推送关键词，出现就丢弃（除非同时有很强的成交信号） */
    private val marketingWords = Regex("优惠券|抽奖|活动|福利|邀请|领取|签到|积分|免费|立即|限时|特惠|开通|升级|赢|攻略|推荐")
    private val strongDealWords = Regex("付款成功|支付成功|成功付款|已支付|已付款|收款到账|退款到账|到账")

    fun parse(pkg: String?, title: String?, text: String?, timeMillis: Long): ParsedBill? {
        val app = PayApp.fromPackage(pkg) ?: return null
        val t = title?.trim().orEmpty()
        val c = text?.trim().orEmpty()
        if (t.isEmpty() && c.isEmpty()) return null
        return when (app) {
            PayApp.WECHAT -> parseWechat(t, c, timeMillis)
            PayApp.ALIPAY -> parseAlipay(t, c, timeMillis)
            PayApp.MANUAL -> null
        }
    }

    private fun parseWechat(title: String, text: String, time: Long): ParsedBill? {
        val prefixed = wechatPrefix.find(text)
        if (title !in wechatPayTitles && prefixed == null) return null
        val body = if (prefixed != null) text.substring(prefixed.range.last + 1) else text
        val full = "$title $body"
        val amount = AmountParser.parse(body) ?: AmountParser.parse(full) ?: return null
        val type = direction(full) ?: return null
        return ParsedBill(
            amountCents = amount,
            type = type,
            app = PayApp.WECHAT,
            source = CaptureSource.NOTIFICATION,
            timeMillis = time,
            merchant = extractMerchant(body),
            rawText = full,
        )
    }

    private fun parseAlipay(title: String, text: String, time: Long): ParsedBill? {
        val full = "$title $text"
        if (marketingWords.containsMatchIn(full) && !strongDealWords.containsMatchIn(full)) return null
        val amount = AmountParser.parse(full) ?: return null
        val type = direction(full) ?: return null
        return ParsedBill(
            amountCents = amount,
            type = type,
            app = PayApp.ALIPAY,
            source = CaptureSource.NOTIFICATION,
            timeMillis = time,
            merchant = extractMerchant(full),
            rawText = full,
        )
    }

    /** 判断收支方向；两类关键词都没有时返回 null（不是账单） */
    fun direction(text: String): BillType? {
        // 「退款」「收款到账」优先于「支付」，因为退款通知里常常也带着「支付」二字
        if (Regex("退款|退回").containsMatchIn(text)) return BillType.INCOME
        val inc = incomeWords.find(text)?.range?.first
        val exp = expenseWords.find(text)?.range?.first
        return when {
            inc == null && exp == null -> null
            inc == null -> BillType.EXPENSE
            exp == null -> BillType.INCOME
            // 两种都出现时，谁先出现听谁的，比如「你向XX付款，对方已收款」→ 支出
            exp <= inc -> BillType.EXPENSE
            else -> BillType.INCOME
        }
    }

    private val merchantPatterns = listOf(
        Regex("""在[「“"]?(.{2,30}?)[」”"]?(?:消费|付款|成功付款|支付)"""),
        Regex("""(?:付款给|转账给|转给|向)[「“"]?(.{1,30}?)[」”"]?(?:付款|转账|支付|成功|，|,|。|\s|$)"""),
        Regex("""(?:商户|商家|收款方)[:：]\s*(\S{2,30})"""),
    )

    fun extractMerchant(text: String): String {
        for (re in merchantPatterns) {
            val v = re.find(text)?.groupValues?.get(1)?.trim()?.trim('，', ',', '。') ?: continue
            if (v.isNotEmpty() && AmountParser.parse(v) == null && v != "你") return v
        }
        return ""
    }
}
