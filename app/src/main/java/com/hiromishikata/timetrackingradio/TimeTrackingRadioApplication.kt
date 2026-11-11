package com.hiromishikata.timetrackingradio

import android.app.Application
import com.hiromishikata.timetrackingradio.adapter.update.UpdateCheckScheduler
import com.hiromishikata.timetrackingradio.adapter.widget.WidgetWorkScheduler
import com.hiromishikata.timetrackingradio.adapter.diagnostics.recordAppStart

class TimeTrackingRadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        recordAppStart(this)
        UpdateCheckScheduler.schedule(this)
        WidgetWorkScheduler.enqueueAppStart(this)
    }
}
