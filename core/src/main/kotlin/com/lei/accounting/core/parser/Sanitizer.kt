package com.lei.accounting.core.parser

/** 发给 DeepSeek 之前做脱敏：长数字（卡号、手机号、订单号）打码，限制长度 */
object Sanitizer {
    private val longDigits = Regex("""\d[\d *]{4,}\d""")

    fun clean(text: String, maxLen: Int = 400): String =
        text.replace(longDigits, "****")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(maxLen)
}
