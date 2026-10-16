package com.lei.accounting

import android.app.Application
import com.lei.accounting.ai.SummaryWorker
import com.lei.accounting.capture.BillRecorder
import com.lei.accounting.system.Notifier

class LeiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        BillRecorder.init(this)
        SummaryWorker.schedule(this)
    }
}
