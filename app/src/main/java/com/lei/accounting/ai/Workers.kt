package com.lei.accounting.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lei.accounting.core.ai.DeepSeekPrompts
import com.lei.accounting.core.summary.DateRange
import com.lei.accounting.core.summary.Period
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillRepository
import com.lei.accounting.data.Prefs
import com.lei.accounting.data.SummaryEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import com.lei.accounting.system.Notifier
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * 把待分类的账单批量交给 DeepSeek。
 * 只在有网络时运行，失败自动退避重试，不会常驻后台耗电。
 */
class AiClassifyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.aiReady) return Result.success()
        val repo = BillRepository.get(applicationContext)
        val client = DeepSeekClient(prefs.apiKey, prefs.model)
        val pending = repo.bills.pendingAi(limit = 20)
        for (bill in pending) {
            val prompt = DeepSeekPrompts.classify(
                type = bill.billType,
                amountCents = bill.amountCents,
                appLabel = bill.payApp.label,
                merchant = bill.merchant,
                payMethod = bill.payMethod,
                rawText = bill.rawText,
            )
            val reply = try {
                client.chat(prompt, maxTokens = 120)
            } catch (e: DeepSeekClient.ApiException) {
                if (e.retryable) return Result.retry()
                // Key 无效、余额不足：先跳过，等用户修好设置后再手动触发
                return Result.failure()
            } catch (e: Exception) {
                return Result.retry()
            }
            // 解析期间用户可能已经手动改过，重新读一次
            val latest = repo.bills.get(bill.id) ?: continue
            if (latest.aiStatus != AiStatus.PENDING) continue
            val ai = DeepSeekPrompts.parseClassification(reply, latest.billType)
            if (ai == null) {
                repo.bills.update(latest.copy(aiStatus = AiStatus.SKIPPED))
                continue
            }
            repo.bills.update(
                latest.copy(
                    category = ai.category,
                    merchant = latest.merchant.ifBlank { ai.merchant },
                    note = latest.note.ifBlank { ai.note },
                    aiStatus = if (ai.isBill) AiStatus.DONE else AiStatus.SUSPECT,
                ),
            )
        }
        // 一次最多处理 20 条，剩下的再排一次
        if (pending.size == 20) enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<AiClassifyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                // 稍等几秒，让通知和页面两路捕获先合并完，再一起分类
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE：正在运行时追加到队尾，保证新账单一定会被处理
            WorkManager.getInstance(context).enqueueUniqueWork("ai_classify", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/**
 * 每天固定时间跑一次：生成日报；周日额外生成周报；每月最后一天额外生成月报。
 */
class SummaryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val today = LocalDate.now()
        val periods = buildList {
            add(Period.DAY)
            if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) add(Period.WEEK)
            if (today == today.with(TemporalAdjusters.lastDayOfMonth())) add(Period.MONTH)
        }
        for (p in periods) {
            val entity = SummaryGenerator.generate(applicationContext, DateRange.of(p, today), useAi = prefs.aiReady)
            if (prefs.summaryNotify) Notifier.summary(applicationContext, entity)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "daily_summary"

        /** 安排每天 [Prefs.summaryHour] 点左右执行（WorkManager 会有几分钟误差，不影响） */
        fun schedule(context: Context, replace: Boolean = false) {
            val hour = Prefs(context).summaryHour
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(hour, 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes()
            val req = PeriodicWorkRequestBuilder<SummaryWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}

object SummaryGenerator {
    /** 生成并缓存某个周期的总结；AI 不可用时只保存本地文字 */
    suspend fun generate(context: Context, range: DateRange, useAi: Boolean): SummaryEntity {
        val repo = BillRepository.get(context)
        val summary = repo.summary(range)
        val local = summary.toPlainText()
        var aiText = ""
        if (useAi && summary.count > 0) {
            val prefs = Prefs(context)
            aiText = runCatching {
                DeepSeekClient(prefs.apiKey, prefs.model).chat(DeepSeekPrompts.summary(summary), maxTokens = 400).trim()
            }.getOrDefault("")
        }
        val old = repo.summaries.get(range.key)
        val entity = SummaryEntity(
            key = range.key,
            period = range.period.name,
            title = range.title,
            localText = local,
            // AI 这次没成功时，保留上一次的 AI 点评
            aiText = if (useAi && aiText.isBlank()) old?.aiText.orEmpty() else aiText,
        )
        repo.summaries.upsert(entity)
        return entity
    }
}
