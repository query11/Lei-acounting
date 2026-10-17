package com.lei.accounting.capture

import android.content.Context
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.data.AppDatabase
import com.lei.accounting.data.CaptureLogEntity
import com.lei.accounting.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 识别日志：异步写入，不阻塞通知/无障碍回调 */
object CaptureLog {
    const val NOTIFICATION = "通知"
    const val PAGE = "页面"
    const val OCR = "截图"
    const val MANUAL = "手动"

    private const val KEEP = 500
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun add(context: Context, channel: String, pkg: String?, result: String, detail: String = "") {
        val app = context.applicationContext
        if (!Prefs(app).captureLog) return
        val entity = CaptureLogEntity(
            time = System.currentTimeMillis(),
            channel = channel,
            pkg = PayApp.fromPackage(pkg)?.label ?: pkg.orEmpty(),
            result = result,
            detail = detail.take(600),
        )
        scope.launch {
            runCatching {
                val dao = AppDatabase.get(app).logs()
                dao.insert(entity)
                dao.prune(KEEP)
            }
        }
    }

    /** 页面文字摘要：最多 15 条、每条 20 字，方便看清识别到了什么 */
    fun summarize(texts: List<String>): String =
        texts.take(15).joinToString(" | ") { it.replace('\n', ' ').take(20) }
}
