package com.lei.accounting.core.parser

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp

/**
 * 线上付款流程：购物 App → 拉起微信/支付宝收银台 → 输密码 → 自动跳回购物 App 的订单/支付结果页。
 *
 * 这种情况下微信/支付宝的「支付成功」页可能根本不出现，所以分两步识别：
 * 1. [looksLikeCashier]：认出微信/支付宝的收银台，记下金额（读得到的话）；
 * 2. [parseMerchantResult]：随后几分钟内，在购物 App 页面上找「支付成功」+ 金额。
 */
object PaymentFlowParser {

    /** 收银台上的典型文字 */
    private val cashierWords = Regex("确认付款|立即付款|确认支付|立即支付|请输入支付密码|输入支付密码|使用密码|指纹支付|面容支付|免密支付|付款详情|选择付款方式|支付方式")

    /** 收银台不会出现的文字：说明已经付完了，或者是别的页面 */
    private val notCashierWords = Regex("支付成功|付款成功|账单详情|交易成功")

    fun looksLikeCashier(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ")
        return cashierWords.containsMatchIn(joined) && !notCashierWords.containsMatchIn(joined) &&
            texts.any { AmountParser.parseStandalone(it) != null || AmountParser.parse(it) != null }
    }

    /** 收银台上显示的待付金额 */
    fun cashierAmount(texts: List<String>): Long? =
        texts.firstNotNullOfOrNull { AmountParser.parseStandalone(it) }
            ?: AmountParser.parse(texts.joinToString(" "))

    /** 购物 App 结果页的「成功」字样；只看短文本，避免误伤商品描述、聊天内容 */
    private val successRe = Regex("(支付|付款)(成功|完成)|已支付|已付款|订单已支付|交易成功|下单成功")
    private val negativeRe = Regex("未支付|待支付|未付款|待付款|支付失败|付款失败|取消|超时")

    /** 金额前面出现这些词，说明它就是实付金额；「合计」可能是优惠前的价格，优先级低一些 */
    private val strongPaidLabel = Regex("实付|支付金额|付款金额|应付|已付")
    private val weakPaidLabel = Regex("订单金额|合计|共计|总计")

    fun parseMerchantResult(
        payApp: PayApp,
        merchantAppLabel: String,
        rawTexts: List<String>,
        timeMillis: Long,
        expectedAmount: Long?,
        source: CaptureSource,
    ): ParsedBill? {
        val texts = rawTexts.map { it.trim() }.filter { it.isNotEmpty() }
        val successIdx = texts.indexOfFirst { it.length <= 16 && successRe.containsMatchIn(it) && !negativeRe.containsMatchIn(it) }
        if (successIdx < 0) return null

        data class Found(val cents: Long, val idx: Int, val labelRank: Int)
        val found = texts.mapIndexedNotNull { i, t ->
            val cents = AmountParser.parseStandalone(t) ?: AmountParser.parse(t) ?: return@mapIndexedNotNull null
            val context = t + " " + texts.getOrNull(i - 1).orEmpty()
            val rank = when {
                strongPaidLabel.containsMatchIn(context) -> 2
                weakPaidLabel.containsMatchIn(context) -> 1
                else -> 0
            }
            Found(cents, i, rank)
        }
        val amount = expectedAmount?.takeIf { exp -> found.any { it.cents == exp } }
            ?: found.filter { it.labelRank > 0 }.maxByOrNull { it.labelRank }?.cents
            ?: found.firstOrNull { it.idx > successIdx }?.cents
            ?: expectedAmount
            ?: found.firstOrNull()?.cents
            ?: return null

        val shop = texts.withIndex().firstNotNullOfOrNull { (i, t) ->
            Regex("^(店铺|商家|商户|收款方)[:：]?\\s*(.*)$").matchEntire(t)?.groupValues?.get(2)
                ?.ifBlank { texts.getOrNull(i + 1) }
                ?.takeIf { it.length in 2..30 && AmountParser.parseStandalone(it) == null }
        }

        return ParsedBill(
            amountCents = amount,
            type = BillType.EXPENSE,
            app = payApp,
            source = source,
            timeMillis = timeMillis,
            merchant = shop ?: merchantAppLabel,
            payMethod = payApp.label,
            note = if (shop != null && merchantAppLabel.isNotBlank()) merchantAppLabel else "",
            rawText = (listOf("[$merchantAppLabel]") + texts).joinToString(" ").take(500),
        )
    }
}
