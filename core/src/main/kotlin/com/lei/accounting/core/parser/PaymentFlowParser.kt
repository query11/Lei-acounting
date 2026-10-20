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

    /**
     * 结果页的标题：整条文字就是「支付成功」「订单支付成功」「付款完成」，不是一句话。
     * 「交易成功」「已付款」不算：它们是订单列表、订单详情里的状态，聊天里也常见。
     */
    private val successTitleRe = Regex("^(订单)?(支付|付款)(成功|完成)$")

    /** 标题前后 OCR 常多出来的符号（勾、感叹号、空格） */
    private val titleNoise = Regex("[\\s!！✓✔√]")

    /** 成功字样（订单状态那种短文字）：标题之外还有，多半是订单列表 */
    private val successWordRe = Regex("(支付|付款)(成功|完成)|已支付|已付款|交易成功")

    /** 页面上有这些字，说明这一单还没付成功（或者是订单列表），整页都不认 */
    private val unpaidRe = Regex("待支付|待付款|未支付|未付款|支付失败|付款失败|去支付|继续支付")

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
        val successIdx = texts.indexOfFirst { successTitleRe.matches(it.replace(titleNoise, "")) }
        if (successIdx < 0) return null
        if (texts.any { unpaidRe.containsMatchIn(it) }) return null
        // 一页里有两个以上成功字样，当订单列表处理。标题本身可能出现好几次（顶栏、大标题、图标描述），只算一个
        val title = texts[successIdx].replace(titleNoise, "")
        val otherSuccess = texts.any { t ->
            val n = t.replace(titleNoise, "")
            n != title && n.length <= 8 && successWordRe.containsMatchIn(n)
        }
        if (otherSuccess) return null

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
        val amount = when {
            // 读到了收银台金额：页面上要么有这个金额，要么一个金额都没有（只写了「支付成功」）
            expectedAmount != null -> expectedAmount.takeIf { exp -> found.isEmpty() || found.any { it.cents == exp } } ?: return null
            else -> found.filter { it.labelRank > 0 }.maxByOrNull { it.labelRank }?.cents
                ?: found.firstOrNull { it.idx > successIdx }?.cents
                ?: found.firstOrNull()?.cents
                ?: return null
        }

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
