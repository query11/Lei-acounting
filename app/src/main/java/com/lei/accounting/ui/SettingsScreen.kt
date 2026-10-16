package com.lei.accounting.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.ai.SummaryWorker
import com.lei.accounting.system.SystemStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val busy by vm.busy.collectAsStateWithLifecycle()
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("自动记账权限", style = MaterialTheme.typography.titleMedium)
        PermissionCard(
            title = "① 通知使用权",
            desc = "读取「微信支付」「支付宝」的到账/扣款通知。最省电，建议必开。",
            ok = notifOk,
            action = "去开启",
        ) { SystemStatus.openNotificationListenerSettings(context) }
        PermissionCard(
            title = "② 无障碍：支付页面识别",
            desc = "在「支付成功」页面读取金额和商户，只监听微信、支付宝。读不到文字时会截一张图在本机 OCR。" +
                "在「设置 → 无障碍 → 已下载的应用」里找到「Lei记账 · 支付页面识别」打开。",
            ok = a11yOk,
            action = "去开启",
        ) { SystemStatus.openAccessibilitySettings(context) }
        PermissionCard(
            title = "③ 忽略电池优化",
            desc = "防止 Flyme 在锁屏后冻结本 App。本 App 没有常驻前台服务，开了也基本不耗电。",
            ok = batteryOk,
            action = "去设置",
        ) { SystemStatus.requestIgnoreBatteryOptimizations(context) }
        PermissionCard(
            title = "④ 魅族后台管理",
            desc = "Flyme：手机管家 → 权限管理 → 后台管理 → Lei记账 → 允许后台运行；同时打开「自启动」。" +
                "（不同版本菜单名字可能略有不同）",
            ok = null,
            action = "打开手机管家",
        ) { SystemStatus.openFlymeBackgroundSettings(context) }
        Text(
            "提示：如果无障碍或通知使用权的开关是灰色、提示「受限设置」，请到「应用详情 → 右上角 ⋮ → 允许受限设置」后再开启。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { SystemStatus.openAppDetails(context) }) { Text("打开应用详情") }

        Text("后台与提醒", style = MaterialTheme.typography.titleMedium)
        var hide by remember { mutableStateOf(prefs.hideFromRecents) }
        SwitchRow("在最近任务中隐藏", "隐藏后台卡片，识别功能照常工作", hide) {
            hide = it
            prefs.hideFromRecents = it
            (context as? Activity)?.let { MainActivity.applyHideFromRecents(it) }
        }
        var notifyRecord by remember { mutableStateOf(prefs.notifyOnRecord) }
        SwitchRow("记账成功提示", "记下一笔后弹一条静默通知，点开可修改", notifyRecord) {
            notifyRecord = it
            prefs.notifyOnRecord = it
        }
        var ocr by remember { mutableStateOf(prefs.ocrFallback) }
        SwitchRow("截图识别兜底", "页面读不到文字时截图 + 本机 OCR（不上传图片）", ocr) {
            ocr = it
            prefs.ocrFallback = it
        }
        var summaryNotify by remember { mutableStateOf(prefs.summaryNotify) }
        SwitchRow("定时推送总结", "每天推送日报，周日推送周报，月末推送月报", summaryNotify) {
            summaryNotify = it
            prefs.summaryNotify = it
        }
        var hour by remember { mutableIntStateOf(prefs.summaryHour) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("推送时间：每天 $hour:00", Modifier.weight(1f))
            OutlinedButton(onClick = { hour = (hour + 23) % 24 }) { Text("-") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { hour = (hour + 1) % 24 }) { Text("+") }
            Spacer(Modifier.padding(4.dp))
            Button(onClick = {
                prefs.summaryHour = hour
                SummaryWorker.schedule(context, replace = true)
            }) { Text("保存") }
        }

        Text("DeepSeek 智能识别", style = MaterialTheme.typography.titleMedium)
        var aiOn by remember { mutableStateOf(prefs.aiEnabled) }
        SwitchRow("启用 AI 分类与总结", "只发送金额、商户等短文本（长数字已打码），不发截图", aiOn) {
            aiOn = it
            prefs.aiEnabled = it
        }
        var key by remember { mutableStateOf(prefs.apiKey) }
        var model by remember { mutableStateOf(prefs.model) }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API Key（sk-...）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                prefs.apiKey = key
                prefs.model = model
                vm.retryPendingAi()
            }) { Text("保存") }
            OutlinedButton(onClick = { vm.testApi(key, model) }, enabled = !busy && key.isNotBlank()) { Text("测试连接") }
        }
        Text(
            "在 platform.deepseek.com 创建 API Key 并充值几块钱即可。每笔账单分类约几百 token，一个月花费通常不到 1 元。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("数据", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            scope.launch {
                val csv = vm.exportCsv()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Lei记账导出.csv")
                    putExtra(Intent.EXTRA_TEXT, csv)
                }
                context.startActivity(Intent.createChooser(send, "导出账单"))
            }
        }) { Text("导出全部账单（CSV）") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionCard(title: String, desc: String, ok: Boolean?, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                when (ok) {
                    true -> Icon(Icons.Default.CheckCircle, contentDescription = "已开启", tint = IncomeColor)
                    false -> Icon(Icons.Default.Warning, contentDescription = "未开启", tint = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall)
            if (ok != true) {
                TextButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
