package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshotRepository

class FakeWidgetSnapshotRepository(initial: WidgetSnapshot) : WidgetSnapshotRepository {
    val writes = mutableListOf<WidgetSnapshot>()
    private var current = initial

    override fun read(): WidgetSnapshot = current

    override fun write(snapshot: WidgetSnapshot) {
        current = snapshot
        writes += snapshot
    }
}
