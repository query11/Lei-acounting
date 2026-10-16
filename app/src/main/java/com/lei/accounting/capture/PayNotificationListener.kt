package com.lei.accounting.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.parser.NotificationParser

/**
 * 通知监听：系统有新通知时才唤醒，几乎不耗电。
 * 只处理微信、支付宝两个包名，其它通知直接丢弃，内容不会被保存。
 */
class PayNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (PayApp.fromPackage(sbn.packageName) == null) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val parsed = NotificationParser.parse(sbn.packageName, title, text, sbn.postTime) ?: return
        BillRecorder.record(parsed)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // 被系统断开后请求重新绑定（Flyme 清理后台后可能出现）
        requestRebind(ComponentName(this, PayNotificationListener::class.java))
    }
}
