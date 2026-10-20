package com.lei.accounting.core.summary

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 定时总结的时间计算：每天 hour:00 推送一次。
 *
 * 不能直接用「运行时的今天」：系统可能把任务推迟到半夜以后才跑，那样日报会变成新一天的空报告，
 * 周日的周报、月末的月报也会被跳过。
 */
object SummarySchedule {

    /** 时钟偏差可能让任务提前几分钟跑，这几分钟内也算这一次 */
    private const val EARLY_TOLERANCE_MINUTES = 10L

    /**
     * 这一次对应哪一天：找到不晚于现在的最近一次 hour:00，取它前一分钟所在的日期。
     * 22 点 → 当天；0 点 → 前一天。
     */
    fun targetDate(now: LocalDateTime, hour: Int): LocalDate {
        val t = now.plusMinutes(EARLY_TOLERANCE_MINUTES)
        var slot = t.toLocalDate().atTime(hour, 0)
        if (slot.isAfter(t)) slot = slot.minusDays(1)
        return slot.minusMinutes(1).toLocalDate()
    }

    /** 这一天要出哪些总结：每天日报；周日加周报；月末加月报 */
    fun periodsFor(date: LocalDate): List<Period> = buildList {
        add(Period.DAY)
        if (date.dayOfWeek == DayOfWeek.SUNDAY) add(Period.WEEK)
        if (date == date.with(TemporalAdjusters.lastDayOfMonth())) add(Period.MONTH)
    }

    /** 下一次 hour:00（严格晚于 now），用于第一次安排、改了推送时间之后 */
    fun nextRun(now: LocalDateTime, hour: Int): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, 0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** 某一天的总结跑完后，下一次什么时候跑：按这一天往后排，不受这次推迟或提前的影响 */
    fun nextAfter(date: LocalDate, hour: Int): LocalDateTime {
        // 推送时间是 0 点时，某一天的总结在第二天 0 点出
        val slot = if (hour == 0) date.plusDays(1).atStartOfDay() else date.atTime(hour, 0)
        return slot.plusDays(1)
    }
}
