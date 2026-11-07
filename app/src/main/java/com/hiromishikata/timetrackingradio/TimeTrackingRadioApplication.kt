package com.hiromishikata.timetrackingradio

import android.app.Application
import com.hiromishikata.timetrackingradio.adapter.update.UpdateCheckScheduler

class TimeTrackingRadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateCheckScheduler.schedule(this)
    }
}
