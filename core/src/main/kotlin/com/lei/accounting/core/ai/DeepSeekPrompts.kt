package com.lei.accounting.core.ai

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.core.parser.Sanitizer
import com.lei.accounting.core.rule.Categories
import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.Summary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 一次聊天请求需要的两段文字 */
data class ChatPrompt(val system: String, val user: String, val jsonOutput: Boolean)

@Serializable
data class AiClassification(
    val isBill: Boolean = true,
    val category: String = "",
    val merchant: String = "",
    val note: String = "",
)

/**
 * DeepSeek 提示词。只发送必要的短文本（已脱敏），每次几百 token，成本很低。
 */
object DeepSeekPrompts {

    fun classify(
        type: BillType,
        amountCents: Long,
        appLabel: String,
        merchant: String,
        payMethod: String,
        rawText: String,
    ): ChatPrompt {
        val system = """
你是个人记账助手，负责给一笔微信/支付宝账单归类。
只输出一个 JSON 对象：{"isBill":true,"category":"","merchant":"","note":""}
规则：
1. category 必须从下面列表中选一个，不确定就选「${Categories.default(type)}」：${Categories.of(type).joinToString("、")}
2. merchant：商户或交易对象的简短名称（如「瑞幸咖啡」「滴滴出行」），原文没有就返回空字符串，不要编造。
3. note：一句不超过 12 个字的用途说明（如「午饭外卖」「打车上班」），无法判断就返回空字符串。
4. 如果原文明显不是一笔真实交易（营销推送、聊天内容、余额提醒等），isBill 返回 false。
5. 不要输出 JSON 以外的任何文字。
        """.trimIndent()
        val user = buildJsonObject {
            put("方向", type.label)
            put("金额", amountCents.formatYuan())
            put("来源", appLabel)
            put("已识别商户", merchant)
            put("支付方式", payMethod)
            put("原文", Sanitizer.clean(rawText))
        }.toString()
        return ChatPrompt(system, user, jsonOutput = true)
    }

    fun summary(s: Summary): ChatPrompt {
        val periodName = when (s.range.period) { Period.DAY -> "今天"; Period.WEEK -> "本周"; Period.MONTH -> "本月" }
        val system = """
你是我的私人记账助手，用口语化的中文给我写一段${periodName}的消费总结。
要求：不超过 150 字；先说总支出和最主要的花销；有明显变化（比上期多/少很多、某类突然变多）就指出来；
最后给 1 条具体、可执行的小建议。不要用 markdown，不要编造数据里没有的数字。
        """.trimIndent()
        val user = buildJsonObject {
            put("周期", s.range.title)
            put("总支出", s.totalExpense.formatYuan())
            put("总收入", s.totalIncome.formatYuan())
            put("笔数", s.count)
            s.previousExpense?.let { put("上期支出", it.formatYuan()) }
            put("分类", buildJsonArray {
                s.byCategory.forEach { c ->
                    add(buildJsonObject {
                        put("分类", c.category)
                        put("金额", c.cents.formatYuan())
                        put("笔数", c.count)
                    })
                }
            })
            put("常去商户", buildJsonArray {
                s.topMerchants.forEach { m ->
                    add(buildJsonObject {
                        put("商户", m.merchant)
                        put("金额", m.cents.formatYuan())
                        put("笔数", m.count)
                    })
                }
            })
            s.largest?.let { put("最大一笔", "${it.merchant.ifBlank { it.category }} ¥${it.amountCents.formatYuan()}") }
        }.toString()
        return ChatPrompt(system, user, jsonOutput = false)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 宽松解析模型返回的 JSON（兼容 ```json 代码块包裹） */
    fun parseClassification(content: String, type: BillType): AiClassification? {
        val body = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj: JsonObject = runCatching { json.parseToJsonElement(body.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return null
        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return AiClassification(
            isBill = obj["isBill"]?.jsonPrimitive?.booleanOrNull ?: true,
            category = Categories.normalize(type, str("category")),
            merchant = str("merchant").take(30),
            note = str("note").take(20),
        )
    }
}
