package com.lei.accounting.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lei.accounting.data.Prefs
import com.lei.accounting.system.SystemStatus

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { AppRoot(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyHideFromRecents(this)
        SystemStatus.rebindNotificationListener(this)
        vm.retryPendingAi()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val id = intent.getLongExtra(EXTRA_BILL_ID, 0L)
        if (id > 0) vm.openBill(id)
        val amount = intent.getLongExtra(EXTRA_PREFILL_AMOUNT, 0L)
        if (amount > 0) {
            vm.prefill(
                amountCents = amount,
                appName = intent.getStringExtra(EXTRA_PREFILL_APP).orEmpty(),
                merchant = intent.getStringExtra(EXTRA_PREFILL_MERCHANT).orEmpty(),
                time = intent.getLongExtra(EXTRA_PREFILL_TIME, System.currentTimeMillis()),
            )
            // 处理完清掉，避免旋转屏幕等重建时又弹一次
            intent.removeExtra(EXTRA_PREFILL_AMOUNT)
        }
    }

    companion object {
        const val EXTRA_BILL_ID = "bill_id"
        const val EXTRA_PREFILL_AMOUNT = "prefill_amount"
        const val EXTRA_PREFILL_APP = "prefill_app"
        const val EXTRA_PREFILL_MERCHANT = "prefill_merchant"
        const val EXTRA_PREFILL_TIME = "prefill_time"

        /** 「隐藏后台」：不在最近任务列表里显示本 App（后台服务不受影响） */
        fun applyHideFromRecents(activity: Activity) {
            val hide = Prefs(activity).hideFromRecents
            activity.getSystemService(ActivityManager::class.java)?.appTasks?.forEach {
                runCatching { it.setExcludeFromRecents(hide) }
            }
        }
    }
}
