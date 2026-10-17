package com.lei.accounting.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.core.summary.SummaryCalculator
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** 识别日志：看清楚每一笔是从哪个渠道收到的、为什么没记上 */
@Composable
fun CaptureLogDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("识别日志", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = {
                        scope.launch {
                            val text = vm.exportLogs()
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, "分享识别日志"))
                        }
                    }) { Text("分享") }
                    TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Text(
                    "只保存在本机。漏记时把日志分享出来，可以看出是没收到通知、页面读不到，还是规则没认出来。",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (logs.isEmpty()) {
                    Text("还没有记录。去微信或支付宝付一笔，或打开它们的页面看看。", Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(logs, key = { it.id }) { l ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "${SummaryCalculator.localDateTime(l.time).format(fmt)}  [${l.channel}] ${l.pkg}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(l.result, style = MaterialTheme.typography.bodyMedium)
                            if (l.detail.isNotBlank()) {
                                Text(l.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
