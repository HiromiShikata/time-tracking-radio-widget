package com.hiromishikata.timetrackingradio

import android.app.Application
import com.hiromishikata.timetrackingradio.adapter.update.UpdateCheckScheduler
import com.hiromishikata.timetrackingradio.adapter.widget.WidgetWorkScheduler

class TimeTrackingRadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateCheckScheduler.schedule(this)
        WidgetWorkScheduler.enqueueAppStart(this)
    }
}
