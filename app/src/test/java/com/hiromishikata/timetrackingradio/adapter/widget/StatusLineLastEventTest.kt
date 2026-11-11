package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.usecase.FakeSelectionRepository
import com.hiromishikata.timetrackingradio.usecase.FakeWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.usecase.WidgetSnapshotReadUseCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class StatusLineLastEventTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val factory = SelectionWidgetRemoteViewsFactory(context)
    private val now = Instant.parse("2026-11-09T02:20:00Z")
    private val firstInstant = Instant.parse("2026-11-09T02:19:01Z")
    private val secondInstant = Instant.parse("2026-11-09T02:19:05.100Z")
    private val thirdInstant = Instant.parse("2026-11-09T02:19:08Z")
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val work = CellKey("project-a", ActivityKind.WORK)

    private data class LastEventCase(
        val name: String,
        val fetchedAt: Instant,
        val savedAt: Instant,
        val expectedStateLine: String,
    )

    private fun snapshot(fetchedAt: Instant, savedAt: Instant) = WidgetSnapshot(
        selection = Selection(work, since),
        fetchedAt = fetchedAt,
        outcome = WidgetOutcome.Settled,
        sending = null,
        statusRequest = null,
        savedAt = savedAt,
        roundTripMillis = 2_300,
    )

    @Test
    fun theStateLineShowsWhicheverHappenedLast() {
        val cases = listOf(
            LastEventCase(
                "a save followed by a later successful refresh shows Updated with the refresh time",
                fetchedAt = secondInstant,
                savedAt = firstInstant,
                expectedStateLine = "Updated 02:19:05",
            ),
            LastEventCase(
                "a successful refresh followed by a later save shows Saved with the save time",
                fetchedAt = firstInstant,
                savedAt = secondInstant,
                expectedStateLine = "Saved 02:19:05 (2.3 s)",
            ),
            LastEventCase(
                "a save and a refresh at the same instant shows Saved",
                fetchedAt = secondInstant,
                savedAt = secondInstant,
                expectedStateLine = "Saved 02:19:05 (2.3 s)",
            ),
            LastEventCase(
                "a save, a later refresh and another save shows the latest save",
                fetchedAt = secondInstant,
                savedAt = thirdInstant,
                expectedStateLine = "Saved 02:19:08 (2.3 s)",
            ),
        )
        cases.forEach { case ->
            assertEquals(
                "case=${case.name}",
                case.expectedStateLine,
                stateLineOf(factory.statusText(snapshot(case.fetchedAt, case.savedAt), now)),
            )
        }
    }

    @Test
    fun aRefreshThroughTheReadUseCaseReplacesTheOldSavedLineWithUpdated() {
        val saved = snapshot(fetchedAt = firstInstant, savedAt = firstInstant)
        val selections = FakeSelectionRepository(onRead = { SelectionResponse.Accepted(Selection(work, since)) })
        val snapshots = FakeWidgetSnapshotRepository(saved)
        WidgetSnapshotReadUseCase(selections, snapshots) { secondInstant }.execute()
        assertEquals(
            "Updated 02:19:05",
            stateLineOf(factory.statusText(snapshots.read(), now)),
        )
    }
}
