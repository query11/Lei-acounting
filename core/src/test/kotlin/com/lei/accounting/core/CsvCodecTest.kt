package com.lei.accounting.core

import com.lei.accounting.core.io.CsvCodec
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.PayApp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CsvCodecTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun roundTripOfExportedFormat() {
        // 旧版本「导出全部账单」的格式
        val text = "﻿时间,收支,金额,分类,商户,备注,来源,支付方式\n" +
            CsvCodec.encodeRow(listOf("2026-10-17 13:09", "支出", "-24.70", "购物", "京东", "京东购物", "微信", "")) + "\n" +
            CsvCodec.encodeRow(listOf("2026-10-18 12:31", "支出", "-10.00", "餐饮", "趣智校园, \"一食堂\"", "校园消费", "支付宝", "余额宝")) + "\r\n" +
            CsvCodec.encodeRow(listOf("2026-10-18 09:00", "收入", "1,200.00", "工资", "", "", "手动", ""))
        val r = CsvCodec.decode(text, zone)
        assertEquals(0, r.badLines)
        assertEquals(3, r.bills.size)
        val jd = r.bills[0]
        assertEquals(2470L, jd.amountCents)
        assertEquals(BillType.EXPENSE, jd.type)
        assertEquals(PayApp.WECHAT, jd.app)
        assertEquals(LocalDateTime.of(2026, 10, 17, 13, 9).atZone(zone).toInstant().toEpochMilli(), jd.timeMillis)
        assertEquals("趣智校园, \"一食堂\"", r.bills[1].merchant)
        assertEquals("余额宝", r.bills[1].payMethod)
        assertEquals(120000L, r.bills[2].amountCents)
        assertEquals(BillType.INCOME, r.bills[2].type)
        assertEquals(PayApp.MANUAL, r.bills[2].app)
    }

    @Test fun badLinesCounted() {
        val r = CsvCodec.decode("时间,收支,金额\n昨天,支出,-1\n2026-10-18 10:00,支出,abc\n\n", zone)
        assertEquals(0, r.bills.size)
        assertEquals(2, r.badLines)
    }
}
