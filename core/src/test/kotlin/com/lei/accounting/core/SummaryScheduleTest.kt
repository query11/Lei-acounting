package com.lei.accounting.core

import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.SummarySchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SummaryScheduleTest {
    private fun at(month: Int, day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, month, day, hour, minute)

    @Test fun onTime() {
        // 2026-10-18 是周日：日报 + 周报
        val date = SummarySchedule.targetDate(at(10, 18, 22, 5), hour = 22)
        assertEquals(LocalDate.of(2026, 10, 18), date)
        assertEquals(listOf(Period.DAY, Period.WEEK), SummarySchedule.periodsFor(date))
    }

    @Test fun delayedPastMidnight() {
        // 周日 22 点的任务被系统拖到周一 00:30 才跑：还是周日的日报，也要出周报
        val date = SummarySchedule.targetDate(at(10, 19, 0, 30), hour = 22)
        assertEquals(LocalDate.of(2026, 10, 18), date)
        assertEquals(listOf(Period.DAY, Period.WEEK), SummarySchedule.periodsFor(date))
    }

    @Test fun midnightMeansPreviousDay() {
        // 推送时间设成 0 点：11 月 1 日 00:05 跑的是 10 月 31 日（月末，出月报）
        val date = SummarySchedule.targetDate(at(11, 1, 0, 5), hour = 0)
        assertEquals(LocalDate.of(2026, 10, 31), date)
        assertEquals(listOf(Period.DAY, Period.MONTH), SummarySchedule.periodsFor(date))
    }

    @Test fun slightlyEarlyCountsForThisTime() {
        // 时钟有偏差，21:58 就跑了：算今天这一次，而不是又出一遍昨天的
        assertEquals(LocalDate.of(2026, 10, 18), SummarySchedule.targetDate(at(10, 18, 21, 58), hour = 22))
    }

    @Test fun nextRun() {
        assertEquals(at(10, 18, 22, 0), SummarySchedule.nextRun(at(10, 18, 9, 0), hour = 22))
        assertEquals(at(10, 19, 22, 0), SummarySchedule.nextRun(at(10, 18, 22, 0), hour = 22))
        // 跑完一次后，按这次对应的日期往后排一天，不管这次被拖了多久、是不是提前了几分钟
        assertEquals(at(10, 19, 22, 0), SummarySchedule.nextAfter(LocalDate.of(2026, 10, 18), hour = 22))
        assertEquals(at(11, 2, 0, 0), SummarySchedule.nextAfter(LocalDate.of(2026, 10, 31), hour = 0))
    }
}
