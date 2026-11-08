package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey

class KeyValueQueuedTapRepository(private val store: KeyValueStore) {
    fun read(): CellKey? {
        val project = store.read(PROJECT_KEY) ?: return null
        val activity = ActivityKind.fromCode(store.read(ACTIVITY_KEY).orEmpty()) ?: return null
        return CellKey(project, activity)
    }

    fun write(cell: CellKey?) {
        store.write(PROJECT_KEY, cell?.project)
        store.write(ACTIVITY_KEY, cell?.activity?.code)
    }

    private companion object {
        const val PROJECT_KEY = "queued_tap_project"
        const val ACTIVITY_KEY = "queued_tap_activity"
    }
}
