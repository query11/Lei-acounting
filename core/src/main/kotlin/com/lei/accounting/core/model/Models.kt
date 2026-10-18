package com.lei.accounting.core.model

/** 收支方向 */
enum class BillType(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
}

/** 账单来自哪个 App */
enum class PayApp(val pkg: String, val label: String) {
    WECHAT("com.tencent.mm", "微信"),
    ALIPAY("com.eg.android.AlipayGphone", "支付宝"),
    /** 银行 App / 云闪付 / 银行短信的动账通知 */
    BANK("", "银行卡"),
    MANUAL("", "手动");

    companion object {
        /** 只监听这两个 App，其它包名一律返回 null */
        fun fromPackage(pkg: String?): PayApp? = when (pkg) {
            WECHAT.pkg -> WECHAT
            ALIPAY.pkg -> ALIPAY
            else -> null
        }
    }
}

/** 账单是通过哪种方式捕获的 */
enum class CaptureSource(val label: String) {
    NOTIFICATION("通知"),
    PAGE("页面"),
    OCR("截图识别"),
    MANUAL("手动"),
}

/**
 * 解析器输出的一笔账单。金额统一用「分」存储，避免浮点误差。
 */
data class ParsedBill(
    val amountCents: Long,
    val type: BillType,
    val app: PayApp,
    val source: CaptureSource,
    val timeMillis: Long,
    val merchant: String = "",
    val payMethod: String = "",
    val note: String = "",
    val rawText: String = "",
)

/** 已入库账单在去重/统计时需要的字段 */
data class BillRecord(
    val id: Long,
    val amountCents: Long,
    val type: BillType,
    val app: PayApp,
    val sources: Set<CaptureSource>,
    val timeMillis: Long,
    val category: String,
    val merchant: String = "",
    val payMethod: String = "",
    val note: String = "",
)

fun Long.formatYuan(): String {
    val sign = if (this < 0) "-" else ""
    val abs = kotlin.math.abs(this)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
