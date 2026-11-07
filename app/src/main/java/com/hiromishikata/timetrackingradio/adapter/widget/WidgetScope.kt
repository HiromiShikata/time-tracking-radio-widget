package com.hiromishikata.timetrackingradio.adapter.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

object WidgetScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mutex: Mutex = Mutex()
}
