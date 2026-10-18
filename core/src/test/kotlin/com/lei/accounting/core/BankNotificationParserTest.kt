package com.lei.accounting.core

import com.lei.accounting.core.dedup.BillMerger
import com.lei.accounting.core.model.BillRecord
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.parser.BankNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankNotificationParserTest {

    @Test fun cmbSms() {
        val b = BankNotificationParser.parse("95555", "【招商银行】您账户1234于10月18日12:31在美团消费人民币10.00元，余额1,000.00元", 0)!!
        assertEquals(1000L, b.amountCents)
        assertEquals(BillType.EXPENSE, b.type)
        assertEquals(PayApp.BANK, b.app)
        assertEquals("美团", b.merchant)
        assertEquals("招商银行 尾号1234", b.payMethod)
    }

    @Test fun icbcStyleWithBalanceAfter() {
        val b = BankNotificationParser.parse("工商银行", "您尾号8464卡10月18日12:31支出(消费)25.50元，余额1000.00元【工商银行】", 0)!!
        assertEquals(2550L, b.amountCents)
        assertEquals(BillType.EXPENSE, b.type)
    }

    @Test fun amountBeforeRmbWord() {
        val b = BankNotificationParser.parse("招商银行", "信用卡通知：您尾号1356的招行信用卡消费42.10人民币。", 0)!!
        assertEquals(4210L, b.amountCents)
    }

    @Test fun tenpayGoesToWechat() {
        val b = BankNotificationParser.parse("交易提醒", "您在财付通-美团平台商户有一笔42.10人民币的消费已成功，点击查看详情", 0)
        // 没有卡号也没有银行名时不认（避免误伤），带上信用卡字样就认
        assertNull(b)
        val c = BankNotificationParser.parse("招商银行信用卡", "交易提醒：您在财付通-美团平台商户有一笔42.10人民币的消费已成功", 0)!!
        assertEquals(PayApp.WECHAT, c.app)
        assertEquals("美团平台商户", c.merchant)
    }

    @Test fun unionPay() {
        val b = BankNotificationParser.parse("支付助手：付款成功", "您尾号为9412的银行卡于1日19时22分消费3433.09元", 0)!!
        assertEquals(343309L, b.amountCents)
        assertEquals("尾号9412", b.payMethod)
    }

    @Test fun salaryIncome() {
        val b = BankNotificationParser.parse("建设银行", "您尾号1234的储蓄卡10月18日09:00入账(代发工资)人民币5000.00元，活期余额8000.00元", 0)!!
        assertEquals(500000L, b.amountCents)
        assertEquals(BillType.INCOME, b.type)
    }

    @Test fun transferOut() {
        val b = BankNotificationParser.parse("建设银行", "您尾号1234的储蓄卡10月18日12时31分向张三转账支出人民币200.00元", 0)!!
        assertEquals(20000L, b.amountCents)
        assertEquals(BillType.EXPENSE, b.type)
        assertEquals("张三", b.merchant)
    }

    @Test fun ignoredNotices() {
        assertNull(BankNotificationParser.parse("95588", "您正在使用尾号1234的银行卡支付12.00元，验证码654321，请勿泄露", 0))
        assertNull(BankNotificationParser.parse("招商银行", "您的信用卡本期账单金额1234.00元，最低还款123.40元，还款日10月25日", 0))
        assertNull(BankNotificationParser.parse("招商银行", "您尾号1234信用卡还款1000.00元已到账", 0))
        assertNull(BankNotificationParser.parse("某银行", "您尾号1234的银行卡消费12.00元交易失败", 0))
        assertNull(BankNotificationParser.parse("美团", "您有一张10元优惠券即将过期", 0))
        assertNull(BankNotificationParser.parse("张三", "晚上一起吃饭吗，我请客100元", 0))
    }

    @Test fun bankAlertMergesWithWechatRecord() {
        val wechat = BillRecord(1, 2550, BillType.EXPENSE, PayApp.WECHAT, setOf(CaptureSource.NOTIFICATION), 1_000_000, "餐饮", "瑞幸咖啡")
        val bank = BankNotificationParser.parse("工商银行", "您尾号8464卡支出(消费)25.50元【工商银行】", 1_000_000 + 120_000)!!
        val dup = BillMerger.findDuplicate(bank, listOf(wechat))
        assertNotNull(dup)
        val merged = BillMerger.merge(dup!!, bank)
        assertEquals(PayApp.WECHAT, merged.app)
        assertEquals("瑞幸咖啡", merged.merchant)
        assertTrue(merged.payMethod.contains("工商银行"))
    }
}
