package com.lei.accounting.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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

    val aiReady: Boolean get() = aiEnabled && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}
