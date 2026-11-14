package com.hiromishikata.timetrackingradio

import android.app.Application
import com.hiromishikata.timetrackingradio.adapter.update.UpdateCheckScheduler
import com.hiromishikata.timetrackingradio.adapter.widget.WidgetWorkScheduler
import com.hiromishikata.timetrackingradio.adapter.diagnostics.recordAppStart
import com.hiromishikata.timetrackingradio.adapter.widget.registerDeviceStateReceivers

class TimeTrackingRadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        recordAppStart(this)
        registerDeviceStateReceivers(this)
        UpdateCheckScheduler.schedule(this)
        WidgetWorkScheduler.enqueueAppStart(this)
    }
}
