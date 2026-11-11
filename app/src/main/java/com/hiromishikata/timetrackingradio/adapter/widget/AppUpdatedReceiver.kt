package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) WidgetWorkScheduler.enqueueAppStart(context)
    }
}
