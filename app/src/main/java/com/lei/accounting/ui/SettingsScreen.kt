package com.lei.accounting.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as DrawableIcon
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.BuildConfig
import com.lei.accounting.R
import com.lei.accounting.ai.SummaryWorker
import com.lei.accounting.capture.RecognizeTileService
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.system.SystemStatus
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val busy by vm.busy.collectAsStateWithLifecycle()
    val latestNotification by vm.latestNotification.collectAsStateWithLifecycle()
    val aiError by vm.aiError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 每次回到页面都重新检查权限状态（用户可能刚从系统设置回来）
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val notifOk = remember(refresh) { SystemStatus.notificationListenerEnabled(context) }
    val a11yOk = remember(refresh) { SystemStatus.accessibilityEnabled(context) }
    val batteryOk = remember(refresh) { SystemStatus.ignoringBatteryOptimizations(context) }
    var showLogs by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsvTo)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importFrom)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(0.dp))

        SectionCard("自动识别") {
            SettingRow("🔔", "通知使用权", "读取微信支付、支付宝的扣款和到账通知", onClick = { SystemStatus.openNotificationListenerSettings(context) }) {
                StatusChip(notifOk)
            }
            RowDivider()
            SettingRow(
                "👀",
                "无障碍 · 支付页面识别",
                "读取支付成功页和收银台；读不到文字时在本机截图识别",
                onClick = { SystemStatus.openAccessibilitySettings(context) },
            ) { StatusChip(a11yOk) }
            RowDivider()
            SettingRow("🔋", "忽略电池优化", "防止锁屏后被 Flyme 冻结", onClick = { SystemStatus.requestIgnoreBatteryOptimizations(context) }) {
                StatusChip(batteryOk)
            }
            RowDivider()
            SettingRow("📱", "魅族后台运行", "手机管家 → 权限管理 → 后台管理 → 允许后台运行，并打开自启动", onClick = {
                SystemStatus.openFlymeBackgroundSettings(context)
            }) { Chevron() }
            Text(
                "开关是灰色、提示「受限设置」时：应用详情 → 右上角 ⋮ → 允许受限设置。",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { SystemStatus.openAppDetails(context) }, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)) {
                Text("打开应用详情")
            }
        }

        SectionCard("漏记了怎么办") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("微信 / 支付宝的通知不要关", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "系统「免打扰」没关系，照样能读到。但如果在微信里把「微信支付」设成了消息免打扰，" +
                        "或在系统里关掉了微信/支付宝的通知，它们就不发通知了。不想被打扰就设成「静默、不弹横幅」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    latestNotification?.let { l ->
                        val minutes = (System.currentTimeMillis() - l.time) / 60_000
                        val ago = when {
                            minutes < 1 -> "刚刚"
                            minutes < 60 -> "$minutes 分钟前"
                            minutes < 24 * 60 -> "${minutes / 60} 小时前"
                            else -> "${minutes / (24 * 60)} 天前"
                        }
                        "最近收到：$ago · ${l.pkg} · ${l.result}"
                    } ?: "还没收到过微信/支付宝的通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { SystemStatus.openAppNotificationSettings(context, PayApp.WECHAT.pkg) }) { Text("微信通知") }
                    OutlinedButton(onClick = { SystemStatus.openAppNotificationSettings(context, PayApp.ALIPAY.pkg) }) { Text("支付宝通知") }
                }
            }
            RowDivider()
            SettingRow("⚡", "下拉快捷开关「识别记账」", "停在支付成功页或订单页，下拉点一下就记上") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    FilledTonalButton(onClick = { requestAddTile(context) }) { Text("添加") }
                }
            }
            RowDivider()
            var captureLog by remember { mutableStateOf(prefs.captureLog) }
            ToggleRow("📝", "记录识别日志", "只存本机，最多 500 条", captureLog) {
                captureLog = it
                prefs.captureLog = it
            }
            RowDivider()
            SettingRow("🔍", "查看识别日志", "收到了哪些通知、读到了哪些页面", onClick = { showLogs = true }) { Chevron() }
        }

        SectionCard("后台与提醒") {
            var hide by remember { mutableStateOf(prefs.hideFromRecents) }
            ToggleRow("🙈", "在最近任务中隐藏", "看不到后台卡片，识别照常工作", hide) {
                hide = it
                prefs.hideFromRecents = it
                (context as? Activity)?.let { a -> MainActivity.applyHideFromRecents(a) }
            }
            RowDivider()
            var notifyRecord by remember { mutableStateOf(prefs.notifyOnRecord) }
            ToggleRow("✅", "记账成功提示", "记下一笔后弹一条静默通知，点开可修改", notifyRecord) {
                notifyRecord = it
                prefs.notifyOnRecord = it
            }
            RowDivider()
            var ocr by remember { mutableStateOf(prefs.ocrFallback) }
            ToggleRow("📷", "截图识别兜底", "页面读不到文字时截图 + 本机识别，不上传图片", ocr) {
                ocr = it
                prefs.ocrFallback = it
            }
            RowDivider()
            var summaryNotify by remember { mutableStateOf(prefs.summaryNotify) }
            ToggleRow("📊", "定时推送总结", "每天日报，周日周报，月末月报", summaryNotify) {
                summaryNotify = it
                prefs.summaryNotify = it
            }
            RowDivider()
            var hour by remember { mutableIntStateOf(prefs.summaryHour) }
            SettingRow("⏰", "推送时间", "每天 $hour:00") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−") {
                        hour = (hour + 23) % 24
                        prefs.summaryHour = hour
                        SummaryWorker.schedule(context, replace = true)
                    }
                    Spacer(Modifier.width(6.dp))
                    StepButton("+") {
                        hour = (hour + 1) % 24
                        prefs.summaryHour = hour
                        SummaryWorker.schedule(context, replace = true)
                    }
                }
            }
        }

        SectionCard("DeepSeek 智能识别") {
            var aiOn by remember { mutableStateOf(prefs.aiEnabled) }
            ToggleRow("✨", "启用 AI 分类与总结", "只发送金额、商户等短文本，长数字已打码，不发截图", aiOn) {
                aiOn = it
                prefs.aiEnabled = it
            }
            var key by remember { mutableStateOf(prefs.apiKey) }
            var model by remember { mutableStateOf(prefs.model) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key（sk-...）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (aiOn && aiError.isNotBlank()) {
                    Text(
                        "AI 分类已暂停：$aiError。改好后点「保存」，或者「测试连接」通过，就会接着分类。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        prefs.apiKey = key
                        prefs.model = model
                        prefs.clearAiError()
                        vm.retryPendingAi()
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    }) { Text("保存") }
                    OutlinedButton(onClick = { vm.testApi(key, model) }, enabled = !busy && key.isNotBlank()) { Text("测试连接") }
                }
                Text(
                    "在 platform.deepseek.com 创建 API Key 并充值几块钱即可，一个月通常不到 1 元。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("数据") {
            SettingRow("💾", "导出为 CSV 文件", "保存到手机里，换机或重装前先备份一下", onClick = {
                exportLauncher.launch("Lei记账-${LocalDate.now()}.csv")
            }) { Chevron() }
            RowDivider()
            SettingRow("📥", "从 CSV 文件导入", "导入之前导出的账单，重复的会自动跳过", onClick = {
                importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
            }) { Chevron() }
            RowDivider()
            SettingRow("📋", "粘贴文本导入", "旧版本「分享导出」的文字内容，复制后粘贴进来", onClick = { showPaste = true }) { Chevron() }
            RowDivider()
            SettingRow("📤", "分享导出", "以文字形式发给自己或其它 App", onClick = {
                scope.launch {
                    val csv = vm.exportCsv()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Lei记账导出.csv")
                        putExtra(Intent.EXTRA_TEXT, csv)
                    }
                    context.startActivity(Intent.createChooser(send, "导出账单"))
                }
            }) { Chevron() }
        }

        Text(
            "Lei记账 v${BuildConfig.VERSION_NAME}",
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    if (showLogs) CaptureLogDialog(vm) { showLogs = false }
    if (showPaste) {
        PasteImportDialog(
            onDismiss = { showPaste = false },
            onImport = {
                vm.importPasted(it)
                showPaste = false
            },
        )
    }
}

@Composable
private fun PasteImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "在旧版本里点「导出全部账单」，分享时选「复制」（或发到文件传输助手再复制），然后粘贴到下面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("时间,收支,金额,分类,…") },
                    minLines = 5,
                    maxLines = 10,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 设置项：左侧 emoji 图标 + 标题 + 说明，右侧放状态 / 开关 / 箭头 */
@Composable
private fun SettingRow(
    emoji: String,
    title: String,
    desc: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(desc, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Text(emoji, fontSize = 22.sp) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun ToggleRow(emoji: String, title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(emoji, title, desc, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Chevron() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(40.dp).height(36.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** Android 13+：弹出系统对话框，一键把「识别记账」加到下拉快捷开关 */
private fun requestAddTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
    runCatching {
        sbm.requestAddTileService(
            ComponentName(context, RecognizeTileService::class.java),
            context.getString(R.string.tile_label),
            DrawableIcon.createWithResource(context, R.drawable.ic_stat_bill),
            context.mainExecutor,
        ) { }
    }.onFailure {
        Toast.makeText(context, "这个系统不支持一键添加，请在下拉菜单的编辑页里手动拖进来", Toast.LENGTH_LONG).show()
    }
}
