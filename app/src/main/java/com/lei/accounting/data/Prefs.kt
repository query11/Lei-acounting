package com.lei.accounting.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 简单的本地设置。API Key 只存在本机私有目录（且关闭了备份）。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("deepseek_key", "").orEmpty()
        set(v) = sp.edit { putString("deepseek_key", v.trim()) }

    var model: String
        get() = sp.getString("deepseek_model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit { putString("deepseek_model", v.trim()) }

    var aiEnabled: Boolean
        get() = sp.getBoolean("ai_enabled", true)
        set(v) = sp.edit { putBoolean("ai_enabled", v) }

    /** 是否在「最近任务」里隐藏本 App */
    var hideFromRecents: Boolean
        get() = sp.getBoolean("hide_recents", false)
        set(v) = sp.edit { putBoolean("hide_recents", v) }

    /** 记账成功后弹一条静默通知 */
    var notifyOnRecord: Boolean
        get() = sp.getBoolean("notify_record", true)
        set(v) = sp.edit { putBoolean("notify_record", v) }

    /** 页面读不到文字时，截一张图做 OCR */
    var ocrFallback: Boolean
        get() = sp.getBoolean("ocr_fallback", true)
        set(v) = sp.edit { putBoolean("ocr_fallback", v) }

    /** 每天晚上推送日报 / 周日推送周报 / 月末推送月报 */
    var summaryNotify: Boolean
        get() = sp.getBoolean("summary_notify", true)
        set(v) = sp.edit { putBoolean("summary_notify", v) }

    var summaryHour: Int
        get() = sp.getInt("summary_hour", 22)
        set(v) = sp.edit { putInt("summary_hour", v.coerceIn(0, 23)) }

    /** 记录识别日志（本机保存，用于排查漏记） */
    var captureLog: Boolean
        get() = sp.getBoolean("capture_log", true)
        set(v) = sp.edit { putBoolean("capture_log", v) }

    val aiReady: Boolean get() = aiEnabled && apiKey.isNotBlank()

    /** 最近一次 DeepSeek 不可重试的报错（Key 无效、余额不足）；请求成功或重新保存 Key 时清空 */
    var aiError: String
        get() = sp.getString("ai_error", "").orEmpty()
        set(v) = sp.edit { putString("ai_error", v) }

    fun clearAiError() {
        if (aiError.isNotEmpty()) aiError = ""
    }

    /** AI 能正常用：开着、填了 Key、最近没报错 */
    val aiWorking: Boolean get() = aiReady && aiError.isBlank()

    /** 盯着某个设置：先给当前值，之后每次变化再给一次（后台任务改了，界面也能跟着变） */
    fun <T> observe(read: Prefs.() -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read(this@Prefs)) }
        sp.registerOnSharedPreferenceChangeListener(listener)
        send(read(this@Prefs))
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}
