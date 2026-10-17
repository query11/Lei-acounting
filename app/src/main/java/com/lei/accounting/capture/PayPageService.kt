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
import com.lei.accounting.core.parser.PaymentFlowParser
import com.lei.accounting.data.BillRepository
import com.lei.accounting.data.Prefs
import com.lei.accounting.system.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 读取支付页面的无障碍服务。只读，不点击、不操作任何界面。
 *
 * 两种场景：
 * 1. 线下扫码付款：停在微信/支付宝的「支付成功」页 → 直接识别；
 * 2. 线上付款：购物 App 拉起微信/支付宝收银台，付完自动跳回购物 App。
 *    认出收银台后开一个 3 分钟的「付款窗口」，临时监听所有 App，在购物 App 的结果页找「支付成功」；
 *    确认后（或超时）立刻恢复成只监听微信、支付宝。
 *
 * 省电设计：
 * - 平时只接收微信、支付宝的事件（运行时用 setServiceInfo 动态切换）；
 * - 界面变化做防抖，连续滚动只扫描一次；
 * - 先用 findAccessibilityNodeInfosByText("成功") 粗筛，没有就不遍历整棵树；
 * - 只有「像支付页但读不到文字」时才截图 OCR，同一页面最多 3 次，并且限频。
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

    /** 线上付款：认出收银台后，等待购物 App 显示支付结果 */
    private class PendingPayment(
        val payApp: PayApp,
        var amount: Long?,
        val wallTime: Long,
        var merchantPkg: String? = null,
        var merchantOcr: Int = 0,
    )

    private var pending: PendingPayment? = null
    private val labelCache = HashMap<String, String>()

    private val scanRunnable = Runnable { scan() }
    private val pageProbeRunnable = Runnable { probePage() }
    private val merchantScanRunnable = Runnable { scanMerchantPage() }
    private val windowExpireRunnable = Runnable { onPaymentWindowExpired() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
        setWatchAllApps(false)
        CaptureLog.add(this, CaptureLog.PAGE, null, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val app = PayApp.fromPackage(pkg)
        if (app == null) {
            onOtherAppEvent(pkg)
            return
        }
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
                // 微信新版的支付结果页、收银台上的金额都是原地刷新出来的，不切换 Activity
                if (isPayLikePage() && autoOcrOnThisPage < MAX_AUTO_OCR_PER_PAGE) {
                    handler.removeCallbacks(pageProbeRunnable)
                    handler.postDelayed(pageProbeRunnable, PAGE_PROBE_DELAY_MS)
                }
            }
        }
    }

    private fun isPayLikePage() = PAY_PAGE_HINT.containsMatchIn(lastClassName)

    // ---------------------------------------------------------------- 微信 / 支付宝页面

    private fun scan() {
        val root = rootInActiveWindow ?: return
        val app = PayApp.fromPackage(root.packageName?.toString()) ?: return
        // 粗筛：页面上没有「成功」二字就不可能是支付成功页
        if (root.findAccessibilityNodeInfosByText("成功").isNullOrEmpty()) return
        val texts = collectTexts(root)

        val fp = texts.joinToString("|")
        val now = SystemClock.elapsedRealtime()
        if (fp == lastFingerprint && now - lastFingerprintAt < SAME_PAGE_MS) return

        val p = pending
        val strict = PageTextParser.looksLikeSuccessPage(texts)
        val parsed = when {
            strict -> PageTextParser.parse(app, texts, System.currentTimeMillis(), CaptureSource.PAGE)
            // 付款窗口内放宽：收银台付完后的「支付成功」动画、小程序的结果页都没有「完成」按钮
            p != null -> PaymentFlowParser.parseMerchantResult(p.payApp, "", texts, System.currentTimeMillis(), p.amount, CaptureSource.PAGE)
            else -> null
        }
        if (parsed == null && !strict) return
        lastFingerprint = fp
        lastFingerprintAt = now

        if (parsed != null) {
            BillRecorder.record(parsed)
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "识别为账单 ¥${parsed.amountCents.formatYuan()}", CaptureLog.summarize(texts))
            closePaymentWindow("已在${app.label}页面确认")
        } else {
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "像支付成功页，但没读到金额，改用截图识别", CaptureLog.summarize(texts))
            ocr(app, manual = false)
        }
    }

    /**
     * 页面打开 / 刷新后检查一次：
     * - 记录页面名称（方便排查）；
     * - 认出收银台 → 打开付款窗口；
     * - 支付相关页面却读不到文字（App 不让读、或是自绘页面）→ 截图 OCR。
     */
    private fun probePage() {
        val app = lastApp ?: return
        val root = rootInActiveWindow
        val texts = root?.takeIf { PayApp.fromPackage(it.packageName?.toString()) != null }?.let { collectTexts(it) }.orEmpty()
        if (lastClassName != lastLoggedClassName) {
            lastLoggedClassName = lastClassName
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "打开页面 ${lastClassName.substringAfterLast('.')}，可读文字 ${texts.size} 条", lastClassName)
        }
        when {
            PaymentFlowParser.looksLikeCashier(texts) ->
                openPaymentWindow(app, PaymentFlowParser.cashierAmount(texts), CaptureLog.summarize(texts))
            CASHIER_HINT.containsMatchIn(lastClassName) ->
                openPaymentWindow(app, null, lastClassName)
        }
        // 付款窗口里（刚扫码、刚打开收银台），读不到文字的页面都截图看一眼
        if (texts.size < MIN_READABLE_TEXTS && (isPayLikePage() || pending != null) && autoOcrOnThisPage < MAX_AUTO_OCR_PER_PAGE) {
            autoOcrOnThisPage++
            ocr(app, manual = false)
        }
    }

    // ---------------------------------------------------------------- 付款窗口（线上付款）

    private fun openPaymentWindow(app: PayApp, amount: Long?, detail: String) {
        val p = pending
        if (p == null || p.payApp != app) {
            pending = PendingPayment(app, amount, System.currentTimeMillis())
            setWatchAllApps(true)
            val amountText = amount?.let { "¥${it.formatYuan()}" } ?: "金额未读到"
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "检测到${app.label}收银台（$amountText），开始等待支付结果", detail)
        } else if (p.amount == null && amount != null) {
            p.amount = amount
            CaptureLog.add(this, CaptureLog.PAGE, app.pkg, "收银台金额 ¥${amount.formatYuan()}", detail)
        }
        // 每次看到收银台都重新计时（比如输错密码重来）
        handler.removeCallbacks(windowExpireRunnable)
        handler.postDelayed(windowExpireRunnable, PAYMENT_WINDOW_MS)
    }

    private fun closePaymentWindow(reason: String) {
        val p = pending ?: return
        pending = null
        handler.removeCallbacks(windowExpireRunnable)
        handler.removeCallbacks(merchantScanRunnable)
        setWatchAllApps(false)
        CaptureLog.add(this, CaptureLog.PAGE, p.payApp.pkg, "付款窗口结束：$reason")
    }

    /** 付款窗口超时：没看到支付结果。如果这笔也没从通知里记上，就提醒用户补记 */
    private fun onPaymentWindowExpired() {
        val p = pending ?: return
        val merchant = p.merchantPkg?.let { appLabel(it) }.orEmpty()
        closePaymentWindow("3 分钟内没看到支付成功")
        val amount = p.amount ?: return
        scope.launch {
            val recorded = BillRepository.get(this@PayPageService).bills.since(p.wallTime - 60_000)
                .any { it.amountCents == amount }
            if (!recorded) Notifier.possiblyMissed(this@PayPageService, p.payApp, amount, merchant, p.wallTime)
        }
    }

    private fun onOtherAppEvent(pkg: String) {
        val p = pending ?: return
        if (pkg == packageName || SKIP_PKG.containsMatchIn(pkg)) return
        p.merchantPkg = pkg
        handler.removeCallbacks(merchantScanRunnable)
        handler.postDelayed(merchantScanRunnable, MERCHANT_DEBOUNCE_MS)
    }

    /** 付款窗口内：读购物 App 当前页面，找「支付成功」+ 金额 */
    private fun scanMerchantPage() {
        val p = pending ?: return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (PayApp.fromPackage(pkg) != null || pkg == packageName || SKIP_PKG.containsMatchIn(pkg)) return
        val label = appLabel(pkg)
        val texts = collectTexts(root)
        val parsed = PaymentFlowParser.parseMerchantResult(p.payApp, label, texts, System.currentTimeMillis(), p.amount, CaptureSource.PAGE)
        if (parsed != null) {
            BillRecorder.record(parsed)
            CaptureLog.add(this, CaptureLog.PAGE, pkg, "在「$label」确认支付成功 ¥${parsed.amountCents.formatYuan()}", CaptureLog.summarize(texts))
            closePaymentWindow("已在「$label」确认")
            return
        }
        if (texts.size < MIN_READABLE_TEXTS && p.merchantOcr < MAX_AUTO_OCR_PER_PAGE && prefs.ocrFallback) {
            p.merchantOcr++
            screenshotLines { lines ->
                val current = pending ?: return@screenshotLines
                val r = lines?.let {
                    PaymentFlowParser.parseMerchantResult(current.payApp, label, it, System.currentTimeMillis(), current.amount, CaptureSource.OCR)
                }
                if (r != null) {
                    BillRecorder.record(r)
                    CaptureLog.add(this, CaptureLog.OCR, pkg, "在「$label」截图确认支付成功 ¥${r.amountCents.formatYuan()}", CaptureLog.summarize(lines.orEmpty()))
                    closePaymentWindow("已在「$label」截图确认")
                }
            }
        }
    }

    /** 付款窗口期间监听所有 App；平时只监听微信、支付宝 */
    private fun setWatchAllApps(all: Boolean) {
        val info = serviceInfo ?: return
        info.packageNames = if (all) null else WATCHED_PKGS
        serviceInfo = info
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    // ---------------------------------------------------------------- 手动触发（快捷开关）

    /**
     * 下拉快捷开关「识别记账」触发：收起通知栏 → 读当前页面文字 → 读不到就截图 OCR。
     * 用户主动触发，所以不要求页面上有「完成」按钮；在购物 App 里也能用。
     */
    fun recognizeNow() {
        CaptureLog.add(this, CaptureLog.MANUAL, null, "点击了「识别记账」，开始识别当前页面")
        // 快捷开关会先通过透明页面把通知栏/控制中心收起来；这里再保险收一次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        handler.postDelayed({
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString().orEmpty()
            val texts = root?.let { collectTexts(it) }.orEmpty()
            val parsed = parseManual(pkg, texts, CaptureSource.PAGE)
            if (parsed != null) {
                onManualResult(parsed, texts)
            } else {
                screenshotLines { lines ->
                    if (lines == null) {
                        CaptureLog.add(this, CaptureLog.MANUAL, pkg, "截图失败，这个页面可能禁止截图")
                        feedback("识别失败：这个页面禁止截图")
                    } else {
                        onManualResult(parseManual(pkg, lines, CaptureSource.OCR), lines)
                    }
                }
            }
        }, SHADE_CLOSE_DELAY_MS)
    }

    private fun parseManual(pkg: String, texts: List<String>, source: CaptureSource): ParsedBill? {
        val now = System.currentTimeMillis()
        val payApp = PayApp.fromPackage(pkg)
        if (payApp != null) return PageTextParser.parseManual(payApp, texts, now, source)
        // 在购物 App 里手动触发：优先按「支付结果页」解析，商户名用 App 名称
        val label = if (pkg.isNotEmpty()) appLabel(pkg) else ""
        val p = pending
        return PaymentFlowParser.parseMerchantResult(p?.payApp ?: PayApp.MANUAL, label, texts, now, p?.amount, source)
            ?: PageTextParser.parseManual(PayApp.MANUAL, texts, now, source)?.let { b ->
                if (b.merchant.isBlank()) b.copy(merchant = label) else b
            }
    }

    private fun onManualResult(parsed: ParsedBill?, texts: List<String>) {
        if (parsed != null) {
            BillRecorder.record(parsed)
            CaptureLog.add(this, CaptureLog.MANUAL, parsed.app.pkg, "识别为账单 ¥${parsed.amountCents.formatYuan()}", CaptureLog.summarize(texts))
            // 「已记账」通知由 BillRecorder 发；这里再给一个即时提示
            toast("已记账 ¥${parsed.amountCents.formatYuan()} ${parsed.merchant}")
            closePaymentWindow("已手动确认")
        } else {
            CaptureLog.add(this, CaptureLog.MANUAL, lastApp?.pkg, "没找到金额", CaptureLog.summarize(texts))
            feedback("这一屏没找到金额，请停在支付成功或订单详情页面再试")
        }
    }

    // ---------------------------------------------------------------- 截图 OCR

    /** 微信 / 支付宝页面读不到文字时的自动 OCR */
    private fun ocr(app: PayApp, manual: Boolean) {
        if (!manual) {
            if (!prefs.ocrFallback) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastOcrAt < OCR_INTERVAL_MS) return
            lastOcrAt = now
        }
        screenshotLines { lines ->
            if (lines == null) return@screenshotLines
            val now = System.currentTimeMillis()
            val p = pending
            val parsed = PageTextParser.parse(app, lines, now, CaptureSource.OCR)
                ?: p?.let { PaymentFlowParser.parseMerchantResult(it.payApp, "", lines, now, it.amount, CaptureSource.OCR) }
            when {
                parsed != null -> {
                    BillRecorder.record(parsed)
                    CaptureLog.add(this, CaptureLog.OCR, app.pkg, "识别为账单 ¥${parsed.amountCents.formatYuan()}", CaptureLog.summarize(lines))
                    closePaymentWindow("已截图确认")
                }
                PaymentFlowParser.looksLikeCashier(lines) ->
                    openPaymentWindow(app, PaymentFlowParser.cashierAmount(lines), CaptureLog.summarize(lines))
                else ->
                    CaptureLog.add(this, CaptureLog.OCR, app.pkg, "截图里没有支付成功页", CaptureLog.summarize(lines))
            }
        }
    }

    /** 截一张屏并 OCR，结果在主线程回调；截图失败（安全页面禁止截图）时回调 null */
    private fun screenshotLines(onResult: (List<String>?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bitmap == null) {
                    handler.post { onResult(null) }
                    return
                }
                scope.launch {
                    val lines = OcrHelper.recognize(bitmap)
                    bitmap.recycle()
                    handler.post { onResult(lines) }
                }
            }

            override fun onFailure(errorCode: Int) {
                CaptureLog.add(this@PayPageService, CaptureLog.OCR, lastApp?.pkg, "截图失败（错误码 $errorCode，输密码等安全页面会禁止截图）")
                handler.post { onResult(null) }
            }
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 失败提示：Toast 之外再发一条通知——有的系统会拦截后台 Toast，免得用户以为点了没反应 */
    private fun feedback(msg: String) {
        toast(msg)
        Notifier.manualResult(this, msg)
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

        private val WATCHED_PKGS = arrayOf(PayApp.WECHAT.pkg, PayApp.ALIPAY.pkg)

        private const val DEBOUNCE_MS = 600L
        private const val MERCHANT_DEBOUNCE_MS = 700L
        private const val SAME_PAGE_MS = 5 * 60_000L
        private const val PAGE_PROBE_DELAY_MS = 1_200L
        private const val SHADE_CLOSE_DELAY_MS = 900L
        private const val PAYMENT_WINDOW_MS = 3 * 60_000L
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
            "(?i)wallet|remittance|cashier|pay|order|result|success|lite|msp|h5|nebula|xriver|offline|coinpurse|scan|capture",
        )

        /**
         * 这些页面之后很可能紧跟着一笔付款，看到就打开付款窗口：
         * 被其它 App 拉起的收银台（支付宝 msp / flybird，微信 OrderHandlerUI / WalletPayUI），
         * 扫一扫（微信 BaseScanUI、支付宝 MainCaptureActivity），付款码（微信 WalletOfflineCoinPurseUI、支付宝 onsitepay）。
         */
        private val CASHIER_HINT = Regex("(?i)msp|flybird|cashier|orderhandler|walletpay|scan|capture|offline|coinpurse|onsitepay")

        /** 付款窗口里不需要看的系统界面 */
        private val SKIP_PKG = Regex("(?i)systemui|inputmethod|keyboard|launcher|^android$|com\\.android\\.settings")
    }
}
