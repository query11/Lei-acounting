package com.lei.accounting.core.parser

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp

/**
 * 银行卡动账通知：银行 App、云闪付推送，以及银行短信（短信也会以通知的形式出现，不需要短信权限）。
 *
 * 典型文案：
 * - 【招商银行】您账户1234于10月18日12:31在美团消费人民币10.00元
 * - 您尾号8464卡10月18日12:31支出(消费)25.50元，余额1000.00元【工商银行】
 * - 您尾号1356的招行信用卡消费42.10人民币
 * - 支付助手：付款成功 您尾号为9412的银行卡于1日19时22分消费3433.09元
 *
 * 不记：验证码短信（付款还没完成）、账单/还款提醒、信用卡还款（避免和消费重复计算）、营销。
 *
 * 只认两种来源（[sourceOf]）：短信 App 和银行类 App。聊天、购物 App 里的文字谁都能写，
 * 比如 QQ 群里一句「我工商银行卡刚才消费了500元」，不能当成自己的账。
 */
object BankNotificationParser {

    /** 通知是谁发的 */
    enum class Source {
        /** 短信 App：谁都能发短信，所以必须带卡号尾号才认 */
        SMS,
        /** 银行、信用卡、云闪付 App */
        BANK_APP,
    }

    /** 常见的系统短信 App，读不到默认短信 App 时兜底 */
    private val smsPkgs = setOf(
        "com.android.mms",
        "com.android.messaging",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
    )

    /**
     * 银行、信用卡、云闪付 App 的包名特征（招商银行 cmb.pb、掌上生活 com.cmbchina…、建行/中行 com.chinamworld…、
     * 工行 com.icbc、农行 com.android.bankabc、云闪付 com.unionpay…）。
     * 没认出来的会在识别日志里记一条「忽略：不是银行或短信 App」并带上包名，照着补到这里。
     */
    private val bankAppPkg = Regex(
        "(?i)bank|unionpay|icbc|cmbchina|chinamworld|spdb|cgbchina|citic|psbc|^cmb\\.pb$|^com\\.cib\\.|^com\\.ccb\\.|^com\\.pingan\\.paces",
    )

    /** 判断通知来源；返回 null 表示不是银行动账会来自的 App，直接不处理 */
    fun sourceOf(pkg: String, defaultSmsPkg: String?): Source? = when {
        pkg == defaultSmsPkg || pkg in smsPkgs -> Source.SMS
        bankAppPkg.containsMatchIn(pkg) -> Source.BANK_APP
        else -> null
    }

    /** 必须像是银行卡/账户的动账 */
    private val bankSignal = Regex("尾号\\s*[为是]?\\s*[*＊]?\\s*\\d{3,4}|账户\\s*\\*?\\d{3,4}|银行卡|储蓄卡|借记卡|信用卡|【[^】]{2,12}(银行|信用卡|银联)】|[^\\s]{2,8}银行|云闪付|银联|支付助手")

    private val expenseRe = Regex("消费|支出|支付|付款|扣款|扣费|取现|取款|转出|转账给|向.{1,20}转账")
    private val incomeRe = Regex("入账|收入|存入|转入|到账|退款|退货|代发|工资|汇入|收到")

    /** 这些通知不是一笔已完成的交易 */
    private val ignoreRe = Regex(
        "验证码|动态码|校验码|动态密码|切勿|勿泄露|" +
            "账单金额|本期账单|最低还款|还款日|到期还款|应还|待还|账单日|" +
            "还款|额度|分期|积分|优惠|活动|邀请|抽奖|权益|理财产品|" +
            // 银行、信用卡 App 的营销：「消费满88元可享立减10元」「满100元返20元」「点击领取消费券」
            "满\\d|可享|立减|返现|返\\d|券|领取|" +
            "失败|未成功|撤销|冲正|预授权",
    )

    private val merchantPatterns = listOf(
        Regex("(?:商户|交易商户|对方户名|对方)[:：]\\s*([^，,。;；\\s]{2,30})"),
        Regex("在[「“\"]?([^，,。;；\\s]{2,30}?)[」”\"]?(?:有一笔|消费|支付|付款|快捷支付|网上支付)"),
        Regex("向([^，,。;；\\s]{1,20}?)(?:转账|付款|支付)"),
        Regex("[（(](?:消费|支付|快捷支付)[-—]?([^）)]{2,30})[）)]"),
    )

    /** 银行名：【招商银行】，或文字里的「中国工商银行」「建设银行」（只认汉字，避免把「的银行卡」当成银行名） */
    private val bankNameRe = Regex("【([^】]{2,12})】|((?:中国)?[\\u4e00-\\u9fa5]{2,4}银行)(?!卡)")
    /** 卡号尾号：「尾号1234」「尾号为9412」「尾号*1234」「账户1234」，或打码的卡号「6222****1234」 */
    private val cardTailRe = Regex("(?:尾号|账户|账号)\\s*[为是:：]?\\s*[*＊]*\\s*(\\d{3,4})(?![\\d*＊])|\\d{0,6}[*＊]{2,}(\\d{4})(?!\\d)")

    /** 通知文字看起来跟银行有关（用于日志：只记录这类通知，别的 App 通知一律不记录） */
    fun looksBankRelated(title: String?, text: String?): Boolean {
        val full = "${title.orEmpty()} ${text.orEmpty()}"
        return bankSignal.containsMatchIn(full) && AmountParser.parse(full) != null
    }

    fun parse(title: String?, text: String?, timeMillis: Long, source: Source = Source.BANK_APP): ParsedBill? {
        val full = "${title.orEmpty()} ${text.orEmpty()}".trim()
        if (!bankSignal.containsMatchIn(full)) return null
        if (ignoreRe.containsMatchIn(full)) return null
        // 朋友发来的、营销短信一般不带卡号尾号；银行的动账短信都带
        if (source == Source.SMS && !cardTailRe.containsMatchIn(full)) return null
        val amount = AmountParser.parse(full) ?: return null

        val exp = expenseRe.find(full)?.range?.first
        val inc = incomeRe.find(full)?.range?.first
        val type = when {
            exp == null && inc == null -> return null
            inc == null -> BillType.EXPENSE
            exp == null -> BillType.INCOME
            // 「支出(消费)…已入账」之类同时出现时，谁先出现听谁的
            exp <= inc -> BillType.EXPENSE
            else -> BillType.INCOME
        }

        // 银行卡绑在微信/支付宝里付款时，通知里通常写着「财付通」「支付宝」，归到对应 App 方便和它们的记录合并
        val app = when {
            Regex("财付通|微信").containsMatchIn(full) -> PayApp.WECHAT
            Regex("支付宝").containsMatchIn(full) -> PayApp.ALIPAY
            else -> PayApp.BANK
        }

        val merchant = merchantPatterns.firstNotNullOfOrNull { re ->
            re.find(full)?.groupValues?.get(1)?.trim()
                ?.removePrefix("财付通-")?.removePrefix("支付宝-")
                ?.takeIf { it.isNotBlank() && AmountParser.parse(it) == null && !it.contains("尾号") }
        }.orEmpty()

        val bank = bankNameRe.find(full)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }.orEmpty()
        val tail = cardTailRe.find(full)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }.orEmpty()
        val payMethod = listOf(bank, if (tail.isNotEmpty()) "尾号$tail" else "").filter { it.isNotEmpty() }.joinToString(" ")

        return ParsedBill(
            amountCents = amount,
            type = type,
            app = app,
            source = CaptureSource.NOTIFICATION,
            timeMillis = timeMillis,
            merchant = merchant,
            payMethod = payMethod.ifBlank { "银行卡" },
            rawText = full,
        )
    }
}
