package com.lei.accounting.ai

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lei.accounting.core.ai.DeepSeekPrompts
import com.lei.accounting.core.summary.DateRange
import com.lei.accounting.core.summary.Summary
import com.lei.accounting.core.summary.SummarySchedule
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillRepository
import com.lei.accounting.data.Prefs
import com.lei.accounting.data.SummaryEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import com.lei.accounting.system.Notifier
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

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
                // Key 无效、余额不足：记下来在设置页提示，账单上也不再显示「AI 分类中…」；等用户修好设置后再触发
                prefs.aiError = e.userMessage
                return Result.failure()
            } catch (e: Exception) {
                return Result.retry()
            }
            prefs.clearAiError()
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
 * 每天 [Prefs.summaryHour] 点跑一次：生成日报；周日额外生成周报；每月最后一天额外生成月报。
 * 用一次性任务，每次跑完再排下一次：每 24 小时一次的周期任务会被系统越拖越晚。
 */
class SummaryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val hour = prefs.summaryHour
        // 任务可能被推迟到半夜以后才跑：按「这一次对应哪一天」算，不用运行时的今天
        val date = SummarySchedule.targetDate(LocalDateTime.now(), hour)
        runCatching {
            val repo = BillRepository.get(applicationContext)
            for (p in SummarySchedule.periodsFor(date)) {
                val summary = repo.summary(DateRange.of(p, date))
                // 没有账单就不推一条「¥0.00」
                if (summary.count == 0) continue
                val entity = SummaryGenerator.generate(applicationContext, summary, useAi = prefs.aiReady)
                if (prefs.summaryNotify) Notifier.summary(applicationContext, entity)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(TAG, "生成定时总结失败", it)
        }
        // 出错也要排下一次，不然定时总结就停了
        enqueue(applicationContext, SummarySchedule.nextAfter(date, hour), ExistingWorkPolicy.REPLACE)
        return Result.success()
    }

    companion object {
        private const val TAG = "SummaryWorker"
        private const val NAME = "summary"
        /** v0.5.0 及以前用的周期任务 */
        private const val LEGACY_NAME = "daily_summary"

        /** 安排到下一次 [Prefs.summaryHour] 点；replace = true 时（改了推送时间）取消原来排好的重新排 */
        fun schedule(context: Context, replace: Boolean = false) {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_NAME)
            val next = SummarySchedule.nextRun(LocalDateTime.now(), Prefs(context).summaryHour)
            enqueue(context, next, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        }

        private fun enqueue(context: Context, at: LocalDateTime, policy: ExistingWorkPolicy) {
            val delay = Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<SummaryWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, req)
        }
    }
}

object SummaryGenerator {
    /** 生成并缓存某个周期的总结；AI 不可用时只保存本地文字 */
    suspend fun generate(context: Context, range: DateRange, useAi: Boolean): SummaryEntity =
        generate(context, BillRepository.get(context).summary(range), useAi)

    suspend fun generate(context: Context, summary: Summary, useAi: Boolean): SummaryEntity {
        val repo = BillRepository.get(context)
        val range = summary.range
        val local = summary.toPlainText()
        var aiText = ""
        if (useAi && summary.count > 0) {
            val prefs = Prefs(context)
            aiText = runCatching {
                DeepSeekClient(prefs.apiKey, prefs.model).chat(DeepSeekPrompts.summary(summary), maxTokens = 400).trim()
            }.onSuccess {
                prefs.clearAiError()
            }.onFailure { e ->
                if (e is DeepSeekClient.ApiException && !e.retryable) prefs.aiError = e.userMessage
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
