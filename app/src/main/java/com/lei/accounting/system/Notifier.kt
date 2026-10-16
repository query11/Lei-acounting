package com.lei.accounting.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lei.accounting.R
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.data.BillEntity
import com.lei.accounting.data.SummaryEntity
import com.lei.accounting.data.billType
import com.lei.accounting.data.payApp
import com.lei.accounting.ui.MainActivity

object Notifier {
    private const val CH_RECORD = "record"
    private const val CH_SUMMARY = "summary"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORD, "记账提醒", NotificationManager.IMPORTANCE_LOW).apply {
                description = "自动记下一笔账时的静默提示"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUMMARY, "收支总结", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每日 / 每周 / 每月消费总结"
            },
        )
    }

    private fun openApp(context: Context, billId: Long = 0): PendingIntent =
        PendingIntent.getActivity(
            context,
            billId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_BILL_ID, billId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canPost(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun recorded(context: Context, bill: BillEntity) {
        if (!canPost(context)) return
        val title = "已记账 ${bill.billType.label} ¥${bill.amountCents.formatYuan()}"
        val text = listOf(bill.category, bill.merchant, bill.payApp.label).filter { it.isNotBlank() }.joinToString(" · ")
        val n = NotificationCompat.Builder(context, CH_RECORD)
            .setSmallIcon(R.drawable.ic_stat_bill)
            .setContentTitle(title)
            .setContentText("$text（点我修改）")
            .setContentIntent(openApp(context, bill.id))
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60_000L)
            .build()
        NotificationManagerCompat.from(context).notify(("bill" + bill.id).hashCode(), n)
    }

    fun summary(context: Context, s: SummaryEntity) {
        if (!canPost(context)) return
        val text = s.aiText.ifBlank { s.localText }
        val label = when (s.period) { "DAY" -> "今日"; "WEEK" -> "本周"; else -> "本月" }
        val n = NotificationCompat.Builder(context, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_bill)
            .setContentTitle("${label}收支总结 · ${s.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(s.key.hashCode(), n)
    }
}
