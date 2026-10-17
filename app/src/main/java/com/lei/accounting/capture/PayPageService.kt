package com.lei.accounting.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.ParsedBill
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.model.formatYuan
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
 * 不依赖通知：即使微信支付的通知被关掉，付完款停在「支付成功」页时也能记上。
 *
 * 省电设计：
 * 1. accessibility_service_config.xml 里限定只接收微信、支付宝的事件；
 * 2. 界面变化做 600ms 防抖，连续滚动只扫描一次；
 * 3. 先用系统自带的 findAccessibilityNodeInfosByText("成功") 粗筛，没有就直接返回，不做整树遍历；
 * 4. 只有「像支付页但读不到文字」时，才截一张图做本地 OCR，同一页面最多 3 次，并且限频。
 */
class PayPageService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    private var lastApp: PayApp? = null
    private var lastClassName = ""
    private var lastLoggedClassName = ""
    private var autoOcrOnThisPage = 0
    private var lastFingerprint: String = ""
    private var lastFingerprintAt = 0L
    private var lastOcrAt = 0L

    private val scanRunnable = Runnable { scan() }
    private val pageProbeRunnable = Runnable { probePage() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
        CaptureLog.add(this, CaptureLog.PAGE, null, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val app = PayApp.fromPackage(event.packageName?.toString()) ?: return
        lastApp = app
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, DEBOUNCE_MS)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString().orEmpty()
                // 只有切到真正的新页面（Activity）才重置；弹窗、Toast 也会触发这个事件
                if (cls.isNotEmpty() && !cls.startsWith("android.")) {
                    if (cls != lastClassName) autoOcrOnThisPage = 0
                    lastClassName = cls
                }
                handler.removeCallbacks(pageProbeRunnable)
                handler.postDelayed(pageProbeRunnable, PAGE_PROBE_DELAY_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 微信新版的支付结果页是「小程序式」页面，结果出来时不切换 Activity，只刷新内容
                if (isPayLikePage() && autoOcrOnThisPage < MAX_AUTO_OCR_PER_PAGE) {
                    handler.removeCallbacks(pageProbeRunnable)
                    handler.postDelayed(pageProbeRunnable, PAGE_PROBE_DELAY_MS)
                }
            }
        }
    }

    private fun isPayLikePage() = PAY_PAGE_HINT.containsMatchIn(lastClassName)

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
        if (parsed != null) {
            BillRecorder.record(parsed)
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "识别为账单 ¥${parsed.amountCents.formatYuan()}", CaptureLog.summarize(texts))
        } else {
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "像支付成功页，但没读到金额，改用截图识别", CaptureLog.summarize(texts))
            ocr(app, manual = false)
        }
    }

    /**
     * 页面打开 / 刷新后检查一次：记录页面名称（方便排查），
     * 如果是支付相关页面但读不到文字（App 不让无障碍读取，或是自绘页面），就截图 OCR。
     */
    private fun probePage() {
        val app = lastApp ?: return
        val root = rootInActiveWindow
        val count = root?.let { collectTexts(it).size } ?: 0
        if (lastClassName != lastLoggedClassName) {
            lastLoggedClassName = lastClassName
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "打开页面 ${lastClassName.substringAfterLast('.')}，可读文字 $count 条", lastClassName)
        }
        if (count < MIN_READABLE_TEXTS && isPayLikePage() && autoOcrOnThisPage < MAX_AUTO_OCR_PER_PAGE) {
            autoOcrOnThisPage++
            ocr(app, manual = false)
        }
    }

    /**
     * 下拉快捷开关「识别记账」触发：收起通知栏 → 读当前页面文字 → 读不到就截图 OCR。
     * 用户主动触发，所以不要求页面上有「完成」按钮。
     */
    fun recognizeNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        handler.postDelayed({
            val root = rootInActiveWindow
            val app = PayApp.fromPackage(root?.packageName?.toString()) ?: PayApp.MANUAL
            val texts = root?.let { collectTexts(it) }.orEmpty()
            val parsed = PageTextParser.parseManual(app, texts, System.currentTimeMillis(), CaptureSource.PAGE)
            if (parsed != null) {
                onManualResult(parsed, texts)
            } else {
                ocr(app, manual = true)
            }
        }, SHADE_CLOSE_DELAY_MS)
    }

    private fun onManualResult(parsed: ParsedBill?, texts: List<String>) {
        if (parsed != null) {
            BillRecorder.record(parsed)
            CaptureLog.add(this, CaptureLog.MANUAL, parsed.app.pkg, "识别为账单 ¥${parsed.amountCents.formatYuan()}", CaptureLog.summarize(texts))
            toast("已记账 ¥${parsed.amountCents.formatYuan()} ${parsed.merchant}")
        } else {
            CaptureLog.add(this, CaptureLog.MANUAL, lastApp?.pkg, "没找到金额", CaptureLog.summarize(texts))
            toast("这一屏没找到金额，请停在「支付成功」页面再试")
        }
    }

    private fun ocr(app: PayApp, manual: Boolean) {
        if (!manual) {
            if (!prefs.ocrFallback) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastOcrAt < OCR_INTERVAL_MS) return
            lastOcrAt = now
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bitmap == null) {
                    if (manual) handler.post { onManualResult(null, emptyList()) }
                    return
                }
                scope.launch {
                    val lines = OcrHelper.recognize(bitmap)
                    bitmap.recycle()
                    val now = System.currentTimeMillis()
                    if (manual) {
                        val parsed = PageTextParser.parseManual(app, lines, now, CaptureSource.OCR)
                        handler.post { onManualResult(parsed, lines) }
                        return@launch
                    }
                    val parsed = PageTextParser.parse(app, lines, now, CaptureSource.OCR)
                    if (parsed != null) {
                        BillRecorder.record(parsed)
                        CaptureLog.add(this@PayPageService, CaptureLog.OCR, app.pkg, "识别为账单 ¥${parsed.amountCents.formatYuan()}", CaptureLog.summarize(lines))
                    } else {
                        CaptureLog.add(this@PayPageService, CaptureLog.OCR, app.pkg, "截图里没有支付成功页", CaptureLog.summarize(lines))
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                // 输入密码等安全页面禁止截图，这是正常现象
                CaptureLog.add(this@PayPageService, CaptureLog.OCR, app.pkg, "截图失败（错误码 $errorCode，安全页面会禁止截图）")
                if (manual) handler.post { toast("截图失败，这个页面可能禁止截图") }
            }
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        /** 快捷开关通过它触发识别；null 表示无障碍服务没开 */
        @Volatile
        var instance: PayPageService? = null
            private set

        private const val DEBOUNCE_MS = 600L
        private const val SAME_PAGE_MS = 5 * 60_000L
        private const val PAGE_PROBE_DELAY_MS = 1_200L
        private const val SHADE_CLOSE_DELAY_MS = 700L
        private const val OCR_INTERVAL_MS = 4_000L
        private const val MAX_AUTO_OCR_PER_PAGE = 3
        private const val MAX_NODES = 400
        private const val MIN_READABLE_TEXTS = 3

        /**
         * 支付相关页面的类名特征。
         * 微信：钱包/收银台/转账页，以及新版用「Lite」小程序页面展示支付结果；
         * 支付宝：收银台 MspContainerActivity、H5/小程序容器。
         * 实际类名可以在「识别日志」里看到，不准的话按日志调整这里。
         */
        private val PAY_PAGE_HINT = Regex(
            "(?i)wallet|remittance|cashier|pay|order|result|success|lite|msp|h5|nebula|xriver",
        )
    }
}
