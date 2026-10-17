package com.lei.accounting.core

import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.parser.AmountParser
import com.lei.accounting.core.parser.NotificationParser
import com.lei.accounting.core.parser.PageTextParser
import com.lei.accounting.core.parser.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextParserTest {

    @Test fun wechatSuccessPage() {
        val texts = listOf("支付成功", "瑞幸咖啡", "¥", "9.90", "支付方式", "零钱", "完成")
        val b = PageTextParser.parse(PayApp.WECHAT, texts, 0)!!
        assertEquals(990L, b.amountCents)
        assertEquals("瑞幸咖啡", b.merchant)
        assertEquals("零钱", b.payMethod)
        assertEquals(BillType.EXPENSE, b.type)
    }

    @Test fun alipaySuccessPageWithLabels() {
        val texts = listOf("支付成功", "￥25.00", "收款方", "XX便利店", "付款方式", "余额宝", "已优惠¥2.00", "完成")
        val b = PageTextParser.parse(PayApp.ALIPAY, texts, 0)!!
        assertEquals(2500L, b.amountCents)
        assertEquals("XX便利店", b.merchant)
        assertEquals("余额宝", b.payMethod)
    }

    @Test fun merchantAfterAmount() {
        val texts = listOf("付款成功", "-36.50", "肯德基(南山店)", "完成")
        val b = PageTextParser.parse(PayApp.ALIPAY, texts, 0)!!
        assertEquals(3650L, b.amountCents)
        assertEquals("肯德基(南山店)", b.merchant)
    }

    @Test fun chatMentionIsNotSuccessPage() {
        // 聊天列表里出现「支付成功」但没有「完成」按钮
        val texts = listOf("微信支付", "支付成功", "¥42.10", "昨天")
        assertFalse(PageTextParser.looksLikeSuccessPage(texts))
        assertNull(PageTextParser.parse(PayApp.WECHAT, texts, 0))
    }

    @Test fun billDetailPageIgnored() {
        val texts = listOf("账单详情", "-42.10", "交易成功", "支付时间", "2026-10-16 12:01")
        assertNull(PageTextParser.parse(PayApp.ALIPAY, texts, 0))
    }

    @Test fun amountParserVariants() {
        assertEquals(123400L, AmountParser.parse("消费人民币1,234.00元"))
        assertEquals(500L, AmountParser.parse("实付5元"))
        assertNull(AmountParser.parseStandalone("2026"))
        assertEquals(4210L, AmountParser.parseStandalone("¥42.1"))
        assertEquals(4200L, AmountParser.parseStandalone("42.00"))
    }

    @Test fun sanitizerMasksLongNumbers() {
        val s = Sanitizer.clean("订单号 4200001234567890 手机 13800138000 金额¥9.90")
        assertFalse(s.contains("4200001234567890"))
        assertFalse(s.contains("13800138000"))
        assertTrue(s.contains("¥9.90"))
    }
}

class ManualParseTest {
    @Test fun ocrScreenWithStatusBarNoDoneButton() {
        // 手动触发时 OCR 读到的整屏文字：状态栏 + 页面，没有「完成」按钮也要能识别
        val lines = listOf("14:05", "5G", "87%", "支付成功", "罗森(新宿店)", "¥680.00", "支付方式", "零钱")
        val b = PageTextParser.parseManual(PayApp.WECHAT, lines, 0, CaptureSource.OCR)!!
        assertEquals(68000L, b.amountCents)
        assertEquals("罗森(新宿店)", b.merchant)
        assertEquals("零钱", b.payMethod)
        // 自动模式仍然要求「完成」按钮
        assertNull(PageTextParser.parse(PayApp.WECHAT, lines, 0, CaptureSource.OCR))
    }

    @Test fun manualWithoutTitle() {
        val lines = listOf("12:30", "肯德基", "-36.50", "交易成功")
        val b = PageTextParser.parseManual(PayApp.ALIPAY, lines, 0, CaptureSource.OCR)!!
        assertEquals(3650L, b.amountCents)
        assertEquals("肯德基", b.merchant)
    }

    @Test fun manualNoAmount() {
        assertNull(PageTextParser.parseManual(PayApp.WECHAT, listOf("聊天", "你好"), 0, CaptureSource.OCR))
    }

    @Test fun wechatPayNotificationCheck() {
        assertTrue(NotificationParser.isWechatPayNotification("微信支付", "已支付¥1.00"))
        assertTrue(NotificationParser.isWechatPayNotification("微信", "[2条]微信支付: 已支付¥8.00"))
        assertFalse(NotificationParser.isWechatPayNotification("张三", "晚上吃饭吗"))
    }
}
