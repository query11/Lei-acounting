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
}
