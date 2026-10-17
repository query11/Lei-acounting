package com.lei.accounting.capture

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * 透明的过渡页面：快捷开关通过它让系统收起通知栏 / 控制中心（Flyme 的控制中心不一定响应无障碍的收起动作），
 * 启动后立刻关闭，回到刚才的支付页面，再由无障碍服务读取屏幕。
 */
class RecognizeTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = PayPageService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启「Lei记账 · 支付页面识别」无障碍服务", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "正在识别当前页面…", Toast.LENGTH_SHORT).show()
            service.recognizeNow()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
