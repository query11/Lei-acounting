package com.lei.accounting.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 下拉通知栏里的「识别记账」快捷开关。
 * 自动识别漏掉时（比如通知被关了、页面读不到），停在支付成功页下拉点一下即可记账。
 */
class RecognizeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (PayPageService.instance != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = if (PayPageService.instance == null) {
            CaptureLog.add(this, CaptureLog.MANUAL, null, "点击了「识别记账」，但无障碍服务没开")
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            // 先打开透明页面：系统会收起通知栏，页面关闭后回到刚才的 App 再识别
            Intent(this, RecognizeTrampolineActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        collapseAndStart(intent)
    }

    private fun collapseAndStart(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
