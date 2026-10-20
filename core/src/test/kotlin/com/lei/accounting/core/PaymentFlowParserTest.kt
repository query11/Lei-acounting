package com.lei.accounting.core

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.parser.PaymentFlowParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentFlowParserTest {

    @Test fun alipayCashier() {
        val texts = listOf("支付宝", "¥", "128.00", "订单信息", "美团外卖", "付款方式", "余额宝", "确认付款")
        assertTrue(PaymentFlowParser.looksLikeCashier(texts))
        assertEquals(12800L, PaymentFlowParser.cashierAmount(texts))
    }

    @Test fun wechatCashier() {
        val texts = listOf("请输入支付密码", "拼多多", "¥59.90", "零钱")
        assertTrue(PaymentFlowParser.looksLikeCashier(texts))
        assertEquals(5990L, PaymentFlowParser.cashierAmount(texts))
    }

    @Test fun successPageIsNotCashier() {
        assertFalse(PaymentFlowParser.looksLikeCashier(listOf("支付成功", "¥9.90", "支付方式", "零钱", "完成")))
    }

    @Test fun merchantResultUsesExpectedAmount() {
        // 淘宝/京东类的结果页：有很多金额（商品价、优惠、实付），用收银台的金额对上
        val texts = listOf("支付成功", "商品金额", "¥139.00", "优惠", "-¥11.00", "实付款", "¥128.00", "查看订单", "返回首页")
        val b = PaymentFlowParser.parseMerchantResult(PayApp.ALIPAY, "淘宝", texts, 0, 12800L, CaptureSource.PAGE)!!
        assertEquals(12800L, b.amountCents)
        assertEquals(BillType.EXPENSE, b.type)
        assertEquals(PayApp.ALIPAY, b.app)
        assertEquals("淘宝", b.merchant)
    }

    @Test fun merchantResultLabeledAmountWithoutCashierAmount() {
        // 收银台读不到金额（微信不让读）时，用页面上「实付」后面的金额
        val texts = listOf("订单支付成功", "商品合计 ¥45.00", "实付 ¥39.90", "查看订单")
        val b = PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "美团", texts, 0, null, CaptureSource.PAGE)!!
        assertEquals(3990L, b.amountCents)
    }

    @Test fun merchantResultFallsBackToCashierAmount() {
        val texts = listOf("支付成功", "等待商家接单", "查看订单")
        val b = PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "美团外卖", texts, 0, 3200L, CaptureSource.PAGE)!!
        assertEquals(3200L, b.amountCents)
    }

    @Test fun shopLabelBecomesMerchant() {
        val texts = listOf("付款成功", "店铺：优衣库官方旗舰店", "¥199.00")
        val b = PaymentFlowParser.parseMerchantResult(PayApp.ALIPAY, "天猫", texts, 0, null, CaptureSource.PAGE)!!
        assertEquals("优衣库官方旗舰店", b.merchant)
        assertEquals("天猫", b.note)
    }

    @Test fun unpaidOrderIgnored() {
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.ALIPAY, "淘宝", listOf("待支付", "¥128.00", "立即付款"), 0, 12800L, CaptureSource.PAGE))
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.ALIPAY, "淘宝", listOf("支付失败", "¥128.00"), 0, 12800L, CaptureSource.PAGE))
    }

    @Test fun longDescriptionNotTreatedAsSuccess() {
        val texts = listOf("这款商品支持支付成功后七天无理由退货哦亲们快来下单吧", "¥99.00")
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.ALIPAY, "淘宝", texts, 0, null, CaptureSource.PAGE))
    }

    @Test fun orderListIgnored() {
        // 付完款顺手去看订单列表：一页好几个「交易成功」，金额都是以前的订单
        val texts = listOf("我的订单", "全部", "待付款", "待发货", "优衣库官方旗舰店", "交易成功", "¥59.90", "小米之家", "交易成功", "¥1,299.00")
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.ALIPAY, "淘宝", texts, 0, null, CaptureSource.PAGE))
    }

    @Test fun cancelledAtCashierThenOrderList() {
        // 在收银台取消了，回到订单页：这一单还是「待支付」
        val texts = listOf("我的订单", "拼多多旗舰店", "待支付", "¥128.00", "去支付", "某某店", "已签收", "¥35.00", "交易成功")
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "拼多多", texts, 0, 12800L, CaptureSource.PAGE))
    }

    @Test fun chatMessageIgnored() {
        val texts = listOf("消息", "张三", "已付款，记得发货哦", "¥20.00")
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "闲鱼", texts, 0, 9900L, CaptureSource.PAGE))
    }

    @Test fun titleWithOcrNoise() {
        val b = PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "瑞幸咖啡", listOf("✓ 支付成功!", "¥12.00", "返回首页"), 0, null, CaptureSource.OCR)!!
        assertEquals(1200L, b.amountCents)
    }

    @Test fun repeatedTitleIsNotList() {
        // 顶栏标题、大标题、图标描述都写着「支付成功」：还是一个结果页
        val texts = listOf("支付成功", "成功图标", "支付成功", "实付 ¥32.00", "订单已支付，商家正在备货")
        val b = PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "美团", texts, 0, 3200L, CaptureSource.PAGE)!!
        assertEquals(3200L, b.amountCents)
        // 标题之外还有别的成功字样（订单状态）：当成列表，不认
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "美团", listOf("支付成功", "¥32.00", "交易成功", "¥18.00"), 0, null, CaptureSource.PAGE))
    }

    @Test fun amountMustMatchCashier() {
        // 页面上有金额，但没有一个等于收银台金额：不是这一笔的结果页
        val texts = listOf("支付成功", "¥35.00", "查看订单")
        assertNull(PaymentFlowParser.parseMerchantResult(PayApp.WECHAT, "美团", texts, 0, 12800L, CaptureSource.PAGE))
    }
}
