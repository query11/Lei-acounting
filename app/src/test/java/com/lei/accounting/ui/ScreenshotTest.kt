package com.lei.accounting.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.summary.DateRange
import com.lei.accounting.core.summary.Period
import com.lei.accounting.core.summary.SummaryCalculator
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 把主要界面渲染成 PNG（app/build/screenshots/），用于检查排版和视觉效果。
 * 不做像素比对，只负责「画出来给人看」。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.systemDefault()

    private fun bill(daysAgo: Long, hour: Int, cents: Long, category: String, merchant: String, app: PayApp, note: String = "", type: BillType = BillType.EXPENSE) =
        BillEntity(
            id = daysAgo * 100 + hour,
            amountCents = cents,
            type = type.name,
            category = category,
            merchant = merchant,
            note = note,
            app = app.name,
            sources = CaptureSource.PAGE.name,
            time = LocalDate.now().minusDays(daysAgo).atTime(hour, 12).atZone(zone).toInstant().toEpochMilli(),
            aiStatus = AiStatus.DONE,
        )

    private val sample = listOf(
        bill(0, 12, 1000, "餐饮", "趣智校园", PayApp.ALIPAY, "校园消费"),
        bill(0, 9, 1580, "日用", "全家便利店", PayApp.WECHAT, "早餐饮料"),
        bill(1, 22, 2243, "转账", "支付宝(还款)", PayApp.MANUAL, "信贷还款"),
        bill(1, 21, 500, "餐饮", "扫二维码付款-给8零食", PayApp.WECHAT),
        bill(1, 13, 2470, "购物", "京东", PayApp.WECHAT, "京东购物"),
        bill(2, 19, 4290, "交通", "滴滴出行", PayApp.BANK, "打车回家"),
        bill(3, 18, 200000, "工资", "实习工资", PayApp.BANK, type = BillType.INCOME),
        bill(4, 12, 3650, "餐饮", "肯德基", PayApp.ALIPAY),
        bill(5, 20, 9900, "娱乐", "电影票", PayApp.WECHAT, "周末电影"),
    )

    private fun periodData(period: Period): PeriodData {
        val range = DateRange.of(period, LocalDate.now(), zone)
        val records = sample.map { it.toRecord() }
        return PeriodData(
            range = range,
            bills = sample.filter { range.contains(it.time) }.sortedByDescending { it.time },
            summary = SummaryCalculator.compute(range, records, records),
        )
    }

    private fun shot(name: String, topmostWindow: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        val bitmap = runCatching {
            if (topmostWindow) {
                // 底部弹窗在单独的窗口里：取最上层的那个
                val roots = compose.onAllNodes(isRoot())
                roots[roots.fetchSemanticsNodes().size - 1].captureToImage().asAndroidBitmap()
            } else {
                compose.onRoot().captureToImage().asAndroidBitmap()
            }
        }.getOrElse {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val out = File("build/screenshots/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun bills() = shot("1_bills") {
        LeiTheme {
            AppScaffold(tab = 0, onTab = {}, onAdd = {}) {
                BillsContent(Period.WEEK, periodData(Period.WEEK), aiWorking = true, canForward = false, onPeriod = {}, onShift = {}, onBillClick = {})
            }
        }
    }

    @Test
    fun stats() = shot("2_stats") {
        LeiTheme {
            AppScaffold(tab = 1, onTab = {}, onAdd = {}) {
                StatsContent(
                    Period.WEEK,
                    periodData(Period.WEEK),
                    aiText = "本周花了 ¥1,000 左右，餐饮占了一大半。周末那场电影是最大一笔，下周可以试试把外卖换成食堂。",
                    busy = false,
                    canForward = false,
                    onPeriod = {},
                    onShift = {},
                    onGenerate = {},
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun billsDark() = shot("3_bills_dark") {
        LeiTheme {
            AppScaffold(tab = 0, onTab = {}, onAdd = {}) {
                BillsContent(Period.DAY, periodData(Period.DAY), aiWorking = true, canForward = false, onPeriod = {}, onShift = {}, onBillClick = {})
            }
        }
    }

    @Test
    fun statsMonth() = shot("4_stats_month") {
        LeiTheme {
            AppScaffold(tab = 1, onTab = {}, onAdd = {}) {
                StatsContent(Period.MONTH, periodData(Period.MONTH), aiText = "", busy = false, canForward = false, onPeriod = {}, onShift = {}, onGenerate = {})
            }
        }
    }

    @Test
    fun billsMonth() = shot("5_bills_month") {
        LeiTheme {
            AppScaffold(tab = 0, onTab = {}, onAdd = {}) {
                BillsContent(Period.MONTH, periodData(Period.MONTH), aiWorking = true, canForward = false, onPeriod = {}, onShift = {}, onBillClick = {})
            }
        }
    }

    @Test
    fun settings() {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        shot("6_settings") {
            LeiTheme {
                AppScaffold(tab = 2, onTab = {}, onAdd = {}) { SettingsScreen(vm) }
            }
        }
    }

    @Test
    fun editSheet() = shot("7_edit_form") {
        LeiTheme {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.padding(top = 24.dp)) {
                    BillEditForm(bill = sample[1], onSave = {}, onDelete = {}, onReclassify = {})
                }
            }
        }
    }
}
