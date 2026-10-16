package com.lei.accounting.core.parser

/**
 * 从中文支付文案里提取金额（单位：分）。
 *
 * 规则：
 * - 支持「¥12.30」「￥1,234.00」「12.30元」「人民币12.30」等写法；
 * - 跳过「余额 / 可用额度 / 优惠 / 立减 / 原价」后面的金额，它们不是实付金额；
 * - 有多个候选时，优先选紧跟「支付/付款/实付/消费/收款/到账/退款」的那个。
 */
object AmountParser {

    private const val NUM = """(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?"""

    private val patterns = listOf(
        Regex("""[¥￥]\s*-?\s*$NUM"""),
        Regex("""人民币\s*$NUM"""),
        Regex("""$NUM\s*元"""),
    )

    /** 金额前面紧挨着这些词时，说明不是交易金额 */
    private val excludeBefore = Regex("""(余额|可用额度|额度|优惠|立减|已减|减免|原价|抵扣|积分|限额|手续费)[^\d¥￥]{0,4}$""")

    private val preferBefore = Regex("""(支付|付款|实付|消费|收款|到账|退款|转账|扣款|扣费|收到|入账|金额)[^\d¥￥]{0,6}$""")

    data class Candidate(val cents: Long, val start: Int, val preferred: Boolean)

    fun candidates(text: String): List<Candidate> {
        val seen = mutableSetOf<Int>()
        val result = mutableListOf<Candidate>()
        for (re in patterns) {
            for (m in re.findAll(text)) {
                // 同一个数字可能被多个正则命中（如「¥12元」），按数字起点去重
                val numStart = m.groups[1]!!.range.first
                if (!seen.add(numStart)) continue
                val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
                if (excludeBefore.containsMatchIn(before)) continue
                val cents = toCents(m.groups[1]!!.value, m.groups[2]?.value) ?: continue
                if (cents <= 0) continue
                result += Candidate(cents, m.range.first, preferBefore.containsMatchIn(before))
            }
        }
        return result.sortedBy { it.start }
    }

    /** 选出最可能的交易金额 */
    fun parse(text: String): Long? {
        val all = candidates(text)
        return (all.firstOrNull { it.preferred } ?: all.firstOrNull())?.cents
    }

    /** 整个字符串本身就是一个金额，例如支付成功页面上单独的「¥42.10」「-42.10」 */
    private val standalone = Regex("""^[-+]?\s*[¥￥]?\s*$NUM\s*元?$""")

    fun parseStandalone(token: String): Long? {
        val t = token.trim()
        val m = standalone.matchEntire(t) ?: return null
        // 纯整数且没有货币符号时太容易误判（日期、数量），要求有小数或货币符号
        val hasMark = t.contains('¥') || t.contains('￥') || t.contains('元') || m.groups[2] != null
        if (!hasMark) return null
        return toCents(m.groups[1]!!.value, m.groups[2]?.value)?.takeIf { it > 0 }
    }

    fun toCents(intPart: String, fraction: String?): Long? {
        val yuan = intPart.replace(",", "").toLongOrNull() ?: return null
        if (yuan > 10_000_000) return null // 超过一千万显然不是日常消费，多半是订单号
        val frac = (fraction ?: "").padEnd(2, '0').take(2).toLongOrNull() ?: 0
        return yuan * 100 + frac
    }
}
