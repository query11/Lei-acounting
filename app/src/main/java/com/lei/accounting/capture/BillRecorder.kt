package com.lei.accounting.capture

import android.content.Context
import android.util.Log
import com.lei.accounting.ai.AiClassifyWorker
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.data.BillRepository
import com.lei.accounting.data.Prefs
import com.lei.accounting.system.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 所有捕获渠道（通知 / 页面 / OCR）的统一入口 */
object BillRecorder {
    private const val TAG = "BillRecorder"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun record(parsed: ParsedBill) {
        if (!::app.isInitialized) return
        scope.launch {
            runCatching {
                val prefs = Prefs(app)
                val result = BillRepository.get(app).saveCaptured(parsed)
                val bill = when (result) {
                    is BillRepository.SaveResult.Inserted -> result.bill
                    is BillRepository.SaveResult.Merged -> result.bill
                }
                Log.i(TAG, "${result::class.simpleName} #${bill.id} ${bill.amountCents} ${bill.merchant} via ${parsed.source}")
                // 合并时商户名可能更完整了，用同一个通知 id 刷新提示
                if (prefs.notifyOnRecord) Notifier.recorded(app, bill)
                if (prefs.aiReady) AiClassifyWorker.enqueue(app)
            }.onFailure { Log.e(TAG, "保存账单失败", it) }
        }
    }
}
