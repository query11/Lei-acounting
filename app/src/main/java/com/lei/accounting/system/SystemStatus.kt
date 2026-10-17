package com.lei.accounting.system

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import com.lei.accounting.capture.PayNotificationListener
import com.lei.accounting.capture.PayPageService

/** 权限状态检测 + 跳转到对应系统设置页（包含魅族 Flyme 专用入口） */
object SystemStatus {

    fun notificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(context, PayNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, PayPageService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openNotificationListenerSettings(context: Context) =
        start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openAccessibilitySettings(context: Context) =
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val ok = start(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            toastOnFail = false,
        )
        if (!ok) start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 打开某个 App（微信 / 支付宝）的系统通知设置页 */
    fun openAppNotificationSettings(context: Context, pkg: String) {
        val ok = start(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            toastOnFail = false,
        )
        if (!ok) start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    fun openAppDetails(context: Context) =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    /**
     * 魅族「手机管家」里的应用权限页（自启动、后台运行都在这里）。
     * 不同 Flyme 版本入口不一样，依次尝试，都失败就打开系统的应用详情页。
     */
    fun openFlymeBackgroundSettings(context: Context) {
        val candidates = listOf(
            Intent("com.meizu.safe.security.SHOW_APPSEC")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra("packageName", context.packageName),
            Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")),
            Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity")),
            context.packageManager.getLaunchIntentForPackage("com.meizu.safe"),
        )
        for (intent in candidates) {
            if (intent != null && start(context, intent, toastOnFail = false)) return
        }
        openAppDetails(context)
    }

    /** 让系统重新绑定通知监听（被 Flyme 杀掉后自救） */
    fun rebindNotificationListener(context: Context) {
        if (notificationListenerEnabled(context)) {
            NotificationListenerService.requestRebind(ComponentName(context, PayNotificationListener::class.java))
        }
    }

    private fun start(context: Context, intent: Intent, toastOnFail: Boolean = true): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        if (toastOnFail) Toast.makeText(context, "打不开这个设置页，请手动在系统设置里找", Toast.LENGTH_LONG).show()
        false
    } catch (e: SecurityException) {
        false
    }
}
