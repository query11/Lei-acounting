package com.lei.accounting.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.model.formatYuan
import com.lei.accounting.core.parser.BankNotificationParser
import com.lei.accounting.core.parser.NotificationParser
import com.lei.accounting.core.parser.Sanitizer

/**
 * 通知监听：系统有新通知时才唤醒，几乎不耗电。
 * 处理微信、支付宝的支付通知，以及银行动账通知（银行 App、云闪付、银行短信）；其它通知直接丢弃，内容不会被保存。
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
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val app = PayApp.fromPackage(sbn.packageName)
        if (app == null) {
            onOtherAppNotification(sbn, title, text)
            return
        }
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

    /**
     * 其它 App 的通知：银行 App、云闪付、短信（银行动账短信也是以通知形式出现的）。
     * 只有看起来像银行动账的才处理和记日志，其它通知内容既不保存也不记录。
     */
    private fun onOtherAppNotification(sbn: StatusBarNotification, title: String?, text: String?) {
        if (!BankNotificationParser.looksBankRelated(title, text)) return
        val parsed = BankNotificationParser.parse(title, text, sbn.postTime)
        // 日志里给长数字打码（验证码、卡号）
        val detail = Sanitizer.clean("$title | $text", maxLen = 300)
        if (parsed != null) {
            BillRecorder.record(parsed)
            CaptureLog.add(this, CaptureLog.BANK, sbn.packageName, "识别为银行卡账单 ¥${parsed.amountCents.formatYuan()}", detail)
        } else {
            CaptureLog.add(this, CaptureLog.BANK, sbn.packageName, "忽略：验证码、账单提醒或还款等", detail)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        CaptureLog.add(this, CaptureLog.NOTIFICATION, null, "通知监听被系统断开，正在请求重连")
        // 被系统断开后请求重新绑定（Flyme 清理后台后可能出现）
        requestRebind(ComponentName(this, PayNotificationListener::class.java))
    }
}
