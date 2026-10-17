package com.lei.accounting.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.core.parser.NotificationParser

/**
 * 通知监听：系统有新通知时才唤醒，几乎不耗电。
 * 只处理微信、支付宝两个包名，其它通知直接丢弃，内容不会被保存。
 *
 * 注意：系统的「免打扰」只是不响铃、不弹横幅，通知照样会发到这里；
 * 但如果在微信里把「微信支付」设成消息免打扰，或在系统里关掉了微信/支付宝的通知，App 根本不会发通知，这里就收不到。
 */
class PayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureLog.add(this, CaptureLog.NOTIFICATION, null, "通知监听已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = PayApp.fromPackage(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val parsed = NotificationParser.parse(sbn.packageName, title, text, sbn.postTime)
        if (parsed != null) {
            BillRecorder.record(parsed)
            CaptureLog.add(this, CaptureLog.NOTIFICATION, sbn.packageName, "识别为账单 ¥${parsed.amountCents.formatYuan()}", "$title | $text")
        } else if (app == PayApp.WECHAT && !NotificationParser.isWechatPayNotification(title, text)) {
            // 微信聊天消息：只记一笔「收到过」，不记录联系人和内容
            CaptureLog.add(this, CaptureLog.NOTIFICATION, sbn.packageName, "忽略：聊天或其它消息（内容不记录）")
        } else {
            CaptureLog.add(this, CaptureLog.NOTIFICATION, sbn.packageName, "忽略：没认出金额或收支", "$title | $text")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        CaptureLog.add(this, CaptureLog.NOTIFICATION, null, "通知监听被系统断开，正在请求重连")
        // 被系统断开后请求重新绑定（Flyme 清理后台后可能出现）
        requestRebind(ComponentName(this, PayNotificationListener::class.java))
    }
}
