package com.lei.accounting.core

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.parser.NotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationParserTest {
    private val wx = "com.tencent.mm"
    private val ali = "com.eg.android.AlipayGphone"

    @Test fun wechatPaid() {
        val b = NotificationParser.parse(wx, "微信支付", "已支付¥42.10", 0)!!
        assertEquals(4210L, b.amountCents)
        assertEquals(BillType.EXPENSE, b.type)
    }

    @Test fun wechatMergedPrefix() {
        val b = NotificationParser.parse(wx, "微信", "[2条]微信支付: 已支付¥8.00", 0)!!
        assertEquals(800L, b.amountCents)
    }

    @Test fun wechatIncome() {
        val b = NotificationParser.parse(wx, "微信收款助手", "微信支付收款0.01元(朋友到店)", 0)!!
        assertEquals(1L, b.amountCents)
        assertEquals(BillType.INCOME, b.type)
    }

    @Test fun wechatRefund() {
        val b = NotificationParser.parse(wx, "微信支付", "退款到账通知 ¥12.50", 0)!!
        assertEquals(BillType.INCOME, b.type)
        assertEquals(1250L, b.amountCents)
    }

    @Test fun wechatChatIgnored() {
        assertNull(NotificationParser.parse(wx, "张三", "我刚付了¥100", 0))
        assertNull(NotificationParser.parse(wx, "张三", "[转账]请收款", 0))
    }

    @Test fun wechatCouponIgnored() {
        assertNull(NotificationParser.parse(wx, "微信支付", "你有一张优惠券待领取", 0))
    }

    @Test fun alipayPaid() {
        val b = NotificationParser.parse(ali, "支付宝", "你在美宜佳成功付款15.80元", 0)!!
        assertEquals(1580L, b.amountCents)
        assertEquals(BillType.EXPENSE, b.type)
        assertEquals("美宜佳", b.merchant)
    }

    @Test fun alipayReceive() {
        val b = NotificationParser.parse(ali, "收钱码", "张三通过扫码向你付款0.01元", 0)!!
        assertEquals(BillType.INCOME, b.type)
    }

    @Test fun alipayMarketingIgnored() {
        assertNull(NotificationParser.parse(ali, "支付宝", "限时福利！领取10元红包，立即使用", 0))
    }

    @Test fun alipaySkipsBalance() {
        val b = NotificationParser.parse(ali, "支付宝", "余额宝余额12345.67元，你已成功付款20.00元", 0)!!
        assertEquals(2000L, b.amountCents)
    }

    @Test fun otherAppIgnored() {
        assertNull(NotificationParser.parse("com.example", "微信支付", "已支付¥1.00", 0))
    }
}
