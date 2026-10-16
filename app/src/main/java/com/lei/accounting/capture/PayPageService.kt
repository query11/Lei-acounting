package com.lei.accounting.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.parser.PageTextParser
import com.lei.accounting.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 读取微信 / 支付宝「支付成功」页面的无障碍服务。只读，不点击、不操作任何界面。
 *
 * 省电设计：
 * 1. accessibility_service_config.xml 里限定只接收微信、支付宝的事件；
 * 2. 界面变化做 600ms 防抖，连续滚动只扫描一次；
 * 3. 先用系统自带的 findAccessibilityNodeInfosByText("成功") 粗筛，没有就直接返回，不做整树遍历；
 * 4. 只有「像支付页但读不到文字」时，才截一张图做本地 OCR，并且限频。
 */
class PayPageService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    private var lastApp: PayApp? = null
    private var lastFingerprint: String = ""
    private var lastFingerprintAt = 0L
    private var lastOcrAt = 0L

    private val scanRunnable = Runnable { scan() }
    private val ocrProbeRunnable = Runnable { probeUnreadablePage() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val app = PayApp.fromPackage(event.packageName?.toString()) ?: return
        lastApp = app
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, DEBOUNCE_MS)
        // 切换到像「收银台 / 支付结果」的新页面时，额外检查一次页面能不能读到文字
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            PAY_ACTIVITY_HINT.containsMatchIn(event.className?.toString().orEmpty())
        ) {
            handler.removeCallbacks(ocrProbeRunnable)
            handler.postDelayed(ocrProbeRunnable, OCR_PROBE_DELAY_MS)
        }
    }

    private fun scan() {
        val root = rootInActiveWindow ?: return
        val app = PayApp.fromPackage(root.packageName?.toString()) ?: return
        // 粗筛：页面上没有「成功」二字就不可能是支付成功页
        if (root.findAccessibilityNodeInfosByText("成功").isNullOrEmpty()) return
        val texts = collectTexts(root)
        if (!PageTextParser.looksLikeSuccessPage(texts)) return

        val fp = texts.joinToString("|")
        val now = SystemClock.elapsedRealtime()
        if (fp == lastFingerprint && now - lastFingerprintAt < SAME_PAGE_MS) return
        lastFingerprint = fp
        lastFingerprintAt = now

        val parsed = PageTextParser.parse(app, texts, System.currentTimeMillis(), CaptureSource.PAGE)
        if (parsed != null) BillRecorder.record(parsed) else tryOcr(app)
    }

    /** 页面是自绘的、或者 App 不让无障碍读取文字时，用截图 + 本地 OCR 兜底 */
    private fun probeUnreadablePage() {
        val app = lastApp ?: return
        val root = rootInActiveWindow
        val readable = root != null && collectTexts(root).size >= MIN_READABLE_TEXTS
        if (!readable) tryOcr(app)
    }

    private fun tryOcr(app: PayApp) {
        if (!prefs.ocrFallback) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOcrAt < OCR_INTERVAL_MS) return
        lastOcrAt = now
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                bitmap ?: return
                scope.launch {
                    val lines = OcrHelper.recognize(bitmap)
                    bitmap.recycle()
                    val parsed = PageTextParser.parse(app, lines, System.currentTimeMillis(), CaptureSource.OCR)
                    if (parsed != null) BillRecorder.record(parsed)
                }
            }

            override fun onFailure(errorCode: Int) {
                // 输入密码等安全页面禁止截图，这是正常现象
                Log.d(TAG, "截图失败 $errorCode")
            }
        })
    }

    /** 深度优先收集可见文字（最多 [MAX_NODES] 个节点，避免大列表页面耗时） */
    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    out += text
                } else {
                    node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                }
            }
            // 倒序压栈，保证出栈顺序和屏幕上的阅读顺序一致
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(ocrProbeRunnable)
        scope.cancel()
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PayPageService"
        private const val DEBOUNCE_MS = 600L
        private const val SAME_PAGE_MS = 5 * 60_000L
        private const val OCR_PROBE_DELAY_MS = 1_200L
        private const val OCR_INTERVAL_MS = 4_000L
        private const val MAX_NODES = 400
        private const val MIN_READABLE_TEXTS = 3
        private val PAY_ACTIVITY_HINT = Regex("(?i)wallet|remittance|cashier|paysuccess|payresult|orderinfo|h5pay")
    }
}
