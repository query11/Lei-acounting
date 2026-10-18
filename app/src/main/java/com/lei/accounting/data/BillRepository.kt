package com.lei.accounting.data

import android.content.Context
import com.lei.accounting.core.dedup.BillMerger
import com.lei.accounting.core.io.ImportedBill
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.rule.Categories
import com.lei.accounting.core.summary.DateRange
import com.lei.accounting.core.summary.Summary
import com.lei.accounting.core.summary.SummaryCalculator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BillRepository private constructor(context: Context) {
    private val db = AppDatabase.get(context)
    val bills = db.bills()
    val summaries = db.summaries()
    val logs = db.logs()
    private val lock = Mutex()

    sealed interface SaveResult {
        data class Inserted(val bill: BillEntity) : SaveResult
        data class Merged(val bill: BillEntity) : SaveResult
    }

    /**
     * 保存一笔自动捕获的账单：先和最近 10 分钟的账单去重，重复就合并，否则新增。
     * 加锁是因为通知和无障碍几乎会同时上报同一笔支付。
     */
    suspend fun saveCaptured(parsed: ParsedBill): SaveResult = lock.withLock {
        val recent = bills.since(parsed.timeMillis - 10 * 60_000L)
        val dup = BillMerger.findDuplicate(parsed, recent.map { it.toRecord() })
        if (dup != null) {
            val old = recent.first { it.id == dup.id }
            val merged = BillMerger.merge(dup, parsed)
            val newMerchant = merged.merchant != old.merchant
            val updated = old.copy(
                sources = BillMerger.encodeSources(merged.sources),
                app = merged.app.name,
                merchant = merged.merchant,
                payMethod = merged.payMethod,
                note = merged.note,
                time = merged.timeMillis,
                rawText = if (old.rawText.length >= parsed.rawText.length) old.rawText else parsed.rawText,
                // 商户名补上了，并且用户没手动改过，就用新信息重新分类
                category = if (newMerchant && old.aiStatus != AiStatus.USER_EDITED) {
                    Categories.guess(old.billType, merged.merchant, parsed.rawText)
                } else old.category,
                aiStatus = if (newMerchant && old.aiStatus != AiStatus.USER_EDITED) AiStatus.PENDING else old.aiStatus,
            )
            bills.update(updated)
            return SaveResult.Merged(updated)
        }
        val entity = BillEntity(
            amountCents = parsed.amountCents,
            type = parsed.type.name,
            category = Categories.guess(parsed.type, parsed.merchant, parsed.rawText),
            merchant = parsed.merchant,
            payMethod = parsed.payMethod,
            note = parsed.note,
            app = parsed.app.name,
            sources = BillMerger.encodeSources(setOf(parsed.source)),
            time = parsed.timeMillis,
            rawText = parsed.rawText,
        )
        val id = bills.insert(entity)
        SaveResult.Inserted(entity.copy(id = id))
    }

    suspend fun saveManual(
        amountCents: Long,
        type: BillType,
        category: String,
        merchant: String,
        note: String,
        time: Long,
        app: PayApp = PayApp.MANUAL,
    ): Long =
        bills.insert(
            BillEntity(
                amountCents = amountCents,
                type = type.name,
                category = category,
                merchant = merchant,
                note = note,
                app = app.name,
                sources = CaptureSource.MANUAL.name,
                time = time,
                aiStatus = AiStatus.USER_EDITED,
            ),
        )

    /**
     * 导入账单（CSV）。前后 1 分钟内金额、收支都相同的视为已存在，跳过，所以重复导入同一个文件是安全的。
     * @return 导入条数 to 跳过条数
     */
    suspend fun importBills(list: List<ImportedBill>): Pair<Int, Int> = lock.withLock {
        var added = 0
        var skipped = 0
        for (b in list) {
            val nearby = bills.range(b.timeMillis - 60_000, b.timeMillis + 60_000)
            if (nearby.any { it.amountCents == b.amountCents && it.type == b.type.name }) {
                skipped++
                continue
            }
            bills.insert(
                BillEntity(
                    amountCents = b.amountCents,
                    type = b.type.name,
                    category = Categories.normalize(b.type, b.category),
                    merchant = b.merchant,
                    payMethod = b.payMethod,
                    note = b.note,
                    app = b.app.name,
                    sources = CaptureSource.MANUAL.name,
                    time = b.timeMillis,
                    aiStatus = AiStatus.USER_EDITED,
                ),
            )
            added++
        }
        added to skipped
    }

    suspend fun summary(range: DateRange): Summary {
        val prev = range.previous()
        val list = bills.range(prev.startMillis, range.endMillis).map { it.toRecord() }
        return SummaryCalculator.compute(range, list, list)
    }

    companion object {
        @Volatile private var instance: BillRepository? = null
        fun get(context: Context): BillRepository = instance ?: synchronized(this) {
            instance ?: BillRepository(context.applicationContext).also { instance = it }
        }
    }
}
