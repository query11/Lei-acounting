package com.lei.accounting.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lei.accounting.ai.SummaryWorker

/** 开机 / 升级后补排定时总结，并让通知监听重新连上 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SummaryWorker.schedule(context)
        SystemStatus.rebindNotificationListener(context)
    }
}
