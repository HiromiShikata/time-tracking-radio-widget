package com.hiromishikata.timetrackingradio.adapter.widget

import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshotRepository

internal object WidgetStateLock

internal class LockedWidgetSnapshotRepository(private val delegate: WidgetSnapshotRepository) : WidgetSnapshotRepository {
    override fun read(): WidgetSnapshot = synchronized(WidgetStateLock) { delegate.read() }

    override fun write(snapshot: WidgetSnapshot) = synchronized(WidgetStateLock) { delegate.write(snapshot) }
}
