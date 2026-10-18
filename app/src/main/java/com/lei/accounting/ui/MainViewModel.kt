package com.lei.accounting.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lei.accounting.ai.AiClassifyWorker
import com.lei.accounting.ai.DeepSeekClient
import com.lei.accounting.ai.SummaryGenerator
import com.lei.accounting.capture.CaptureLog
import com.lei.accounting.core.ai.ChatPrompt
import com.lei.accounting.core.io.CsvCodec
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.core.rule.Categories
import com.lei.accounting.core.summary.DateRange
import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.Summary
import com.lei.accounting.core.summary.SummaryCalculator
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity
import com.lei.accounting.data.BillRepository
import com.lei.accounting.data.CaptureLogEntity
import com.lei.accounting.data.Prefs
import com.lei.accounting.data.SummaryEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PeriodData(
    val range: DateRange,
    val bills: List<BillEntity>,
    val summary: Summary,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BillRepository.get(app)
    val prefs = Prefs(app)

    private val _period = MutableStateFlow(Period.DAY)
    private val _anchor = MutableStateFlow(LocalDate.now())
    val period: StateFlow<Period> = _period.asStateFlow()

    private val range: StateFlow<DateRange> = combine(_period, _anchor) { p, a -> DateRange.of(p, a) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DateRange.of(Period.DAY, LocalDate.now()))

    /** 当前周期的账单 + 统计（顺带查出上一周期，用于环比） */
    val data: StateFlow<PeriodData?> = range.flatMapLatest { r ->
        val prev = r.previous()
        repo.bills.observeRange(prev.startMillis, r.endMillis).map { all ->
            val records = all.map { it.toRecord() }
            PeriodData(
                range = r,
                bills = all.filter { r.contains(it.time) },
                summary = SummaryCalculator.compute(r, records, records),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedSummary: StateFlow<SummaryEntity?> = range.flatMapLatest { repo.summaries.observe(it.key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 识别日志（最近 300 条） */
    val logs: StateFlow<List<CaptureLogEntity>> = repo.logs.observeRecent(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 最近一次收到微信/支付宝通知的记录，用来判断通知是不是被关掉了 */
    val latestNotification: StateFlow<CaptureLogEntity?> = repo.logs.observeLatest(CaptureLog.NOTIFICATION)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clearLogs() = viewModelScope.launch { repo.logs.clear() }

    suspend fun exportLogs(): String {
        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        return repo.logs.recent(500).joinToString("\n") { l ->
            val t = SummaryCalculator.localDateTime(l.time).format(fmt)
            "$t [${l.channel}] ${l.pkg} ${l.result}" + if (l.detail.isNotBlank()) "\n    ${l.detail}" else ""
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 从「已记账」通知点进来时要打开的账单 */
    private val _editing = MutableStateFlow<BillEntity?>(null)
    val editing: StateFlow<BillEntity?> = _editing.asStateFlow()

    fun setPeriod(p: Period) {
        _period.value = p
        _anchor.value = LocalDate.now()
    }

    fun shift(forward: Boolean) {
        val r = range.value
        _anchor.value = if (forward) r.next().start else r.previous().start
    }

    val canGoForward: Boolean get() = range.value.endExclusive <= LocalDate.now()

    fun consumeMessage() {
        _message.value = null
    }

    fun openBill(id: Long) = viewModelScope.launch {
        _editing.value = repo.bills.get(id)
    }

    /** 「可能漏记」提醒点进来：打开一个填好金额的记账框，用户确认后才保存 */
    fun prefill(amountCents: Long, appName: String, merchant: String, time: Long) {
        val app = runCatching { PayApp.valueOf(appName) }.getOrDefault(PayApp.MANUAL)
        _editing.value = BillEntity(
            amountCents = amountCents,
            type = BillType.EXPENSE.name,
            category = Categories.guess(BillType.EXPENSE, merchant),
            merchant = merchant,
            app = app.name,
            sources = CaptureSource.MANUAL.name,
            time = time,
            aiStatus = AiStatus.USER_EDITED,
        )
    }

    fun edit(bill: BillEntity?) {
        _editing.value = bill
    }

    fun save(bill: BillEntity) = viewModelScope.launch {
        if (bill.id == 0L) {
            repo.saveManual(bill.amountCents, bill.billType, bill.category, bill.merchant, bill.note, bill.time, bill.payApp)
        } else {
            repo.bills.update(bill.copy(aiStatus = AiStatus.USER_EDITED))
        }
        _editing.value = null
    }

    fun delete(bill: BillEntity) = viewModelScope.launch {
        repo.bills.delete(bill)
        _editing.value = null
        _message.value = "已删除"
    }

    /** 让 DeepSeek 重新分类这一笔 */
    fun reclassify(bill: BillEntity) = viewModelScope.launch {
        if (!prefs.aiReady) {
            _message.value = "请先在设置里填写 DeepSeek API Key"
            return@launch
        }
        repo.bills.update(bill.copy(aiStatus = AiStatus.PENDING))
        AiClassifyWorker.enqueue(getApplication())
        _editing.value = null
        _message.value = "已交给 DeepSeek 重新分类，稍等几秒"
    }

    fun generateSummary() = viewModelScope.launch {
        _busy.value = true
        try {
            val useAi = prefs.aiReady
            val s = SummaryGenerator.generate(getApplication(), range.value, useAi)
            _message.value = when {
                !useAi -> "已生成本地总结（填写 API Key 后可获得 AI 点评）"
                s.aiText.isBlank() -> "AI 总结失败，请检查网络或 API Key"
                else -> null
            }
        } finally {
            _busy.value = false
        }
    }

    fun testApi(key: String, model: String) = viewModelScope.launch {
        _busy.value = true
        try {
            val reply = DeepSeekClient(key.trim(), model.trim().ifBlank { Prefs.DEFAULT_MODEL })
                .chat(ChatPrompt("你是一个测试助手。", "只回复两个字：成功", jsonOutput = false), maxTokens = 10)
            _message.value = "连接正常：${reply.trim().take(20)}"
        } catch (e: Exception) {
            _message.value = "连接失败：${e.message?.take(120)}"
        } finally {
            _busy.value = false
        }
    }

    fun retryPendingAi() {
        if (prefs.aiReady) AiClassifyWorker.enqueue(getApplication())
    }

    /** 导出全部账单为 CSV 文本（带 BOM，Excel 打开不乱码） */
    suspend fun exportCsv(): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val rows = repo.bills.all().map { b ->
            val t = SummaryCalculator.localDateTime(b.time).format(fmt)
            val amount = (if (b.billType == BillType.EXPENSE) "-" else "") + b.amountCents.formatYuan()
            CsvCodec.encodeRow(listOf(t, b.billType.label, amount, b.category, b.merchant, b.note, b.payApp.label, b.payMethod))
        }
        return "\uFEFF" + (listOf(CsvCodec.HEADER.joinToString(",")) + rows).joinToString("\n")
    }

    /** 导出到用户选择的文件 */
    fun exportCsvTo(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val result = runCatching {
            val csv = exportCsv()
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入文件")
            }
        }
        _message.value = if (result.isSuccess) "已导出 ${repo.bills.all().size} 笔账单" else "导出失败：${result.exceptionOrNull()?.message}"
    }

    /** 从文件导入 */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val text = runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            _message.value = "读不到这个文件"
            return@launch
        }
        importText(text)
    }

    fun importPasted(text: String) = viewModelScope.launch { importText(text) }

    /** 从粘贴的文本导入（旧版本「分享导出」的内容） */
    suspend fun importText(text: String) {
        val parsed = CsvCodec.decode(text)
        if (parsed.bills.isEmpty()) {
            _message.value = "没有找到可导入的账单" + if (parsed.badLines > 0) "（${parsed.badLines} 行格式不对）" else ""
            return
        }
        val (added, skipped) = repo.importBills(parsed.bills)
        _message.value = buildString {
            append("导入 $added 笔")
            if (skipped > 0) append("，跳过 $skipped 笔重复")
            if (parsed.badLines > 0) append("，${parsed.badLines} 行无法识别")
        }
    }
}
