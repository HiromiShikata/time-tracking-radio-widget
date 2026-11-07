package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class KeyValueWidgetSnapshotRepositoryTest {
    @Test
    fun roundTripsEverySnapshotShape() {
        val fetched = Instant.parse("2026-11-07T08:40:00Z")
        val since = Instant.parse("2026-11-07T08:39:00Z")
        val work = CellKey("project-a", ActivityKind.WORK)
        val meeting = CellKey("project-a", ActivityKind.MEETING)
        val cases = listOf(
            WidgetSnapshot.EMPTY,
            WidgetSnapshot(Selection(work, since), fetched, WidgetOutcome.Settled, null),
            WidgetSnapshot(Selection(work, since), fetched, WidgetOutcome.Unreachable, SelectionRequest.Select(meeting)),
            WidgetSnapshot(null, fetched, WidgetOutcome.Rejected("wrong key"), SelectionRequest.Clear),
        )
        cases.forEach { expected ->
            val store = InMemoryKeyValueStore()
            KeyValueWidgetSnapshotRepository(store).write(expected)
            assertEquals("snapshot=$expected", expected, KeyValueWidgetSnapshotRepository(store).read())
        }
    }
}
