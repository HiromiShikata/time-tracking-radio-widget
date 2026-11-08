package com.hiromishikata.timetrackingradio.adapter.widget

import kotlinx.coroutines.sync.Mutex

object WidgetScope {
    val mutex: Mutex = Mutex()
}
