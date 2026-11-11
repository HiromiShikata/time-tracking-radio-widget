package com.hiromishikata.timetrackingradio

import android.app.Application
import com.hiromishikata.timetrackingradio.adapter.update.UpdateCheckScheduler
import com.hiromishikata.timetrackingradio.adapter.widget.WidgetWorkScheduler
import com.hiromishikata.timetrackingradio.adapter.diagnostics.readAppStartInfo
import com.hiromishikata.timetrackingradio.adapter.update.updateDiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.recordAppStarted

class TimeTrackingRadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        recordAppStarted(updateDiagnosticRecorder(this), readAppStartInfo(this))
        UpdateCheckScheduler.schedule(this)
        WidgetWorkScheduler.enqueueAppStart(this)
    }
}
