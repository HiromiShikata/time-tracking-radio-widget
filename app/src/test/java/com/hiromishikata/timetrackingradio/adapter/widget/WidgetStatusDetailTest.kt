package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetStatusDetailTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val factory = SelectionWidgetRemoteViewsFactory(context)
    private val now = Instant.parse("2026-11-09T02:20:00Z")
    private val tapAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val fetchedAt = Instant.parse("2026-11-09T02:00:00Z")
    private val workSince = Instant.parse("2026-11-07T08:39:00Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val versionName: String
        get() = checkNotNull(context.packageManager.getPackageInfo(context.packageName, 0).versionName)

    private data class StatusCase(val name: String, val snapshot: WidgetSnapshot, val expectedLines: (String) -> List<String>)

    private fun snapshot(
        selection: Selection? = null,
        fetchedAt: Instant? = null,
        outcome: WidgetOutcome = WidgetOutcome.Settled,
        pendingTapAt: Instant? = null,
        sentAt: Instant? = null,
        oldestUnsentTapAt: Instant? = null,
        savedAt: Instant? = null,
        roundTripMillis: Long? = null,
        queuedTapCount: Int = 0,
    ) = WidgetSnapshot(
        selection = selection,
        fetchedAt = fetchedAt,
        outcome = outcome,
        sending = null,
        statusRequest = null,
        pendingTapAt = pendingTapAt,
        sentAt = sentAt,
        oldestUnsentTapAt = oldestUnsentTapAt,
        savedAt = savedAt,
        roundTripMillis = roundTripMillis,
        queuedTapCount = queuedTapCount,
    )

    @Test
    fun theStatusTextShowsFourLinesInEveryState() {
        val cases = listOf(
            StatusCase(
                "recording with no save yet shows the last response time",
                snapshot(selection = Selection(work, workSince), fetchedAt = fetchedAt),
            ) { app -> listOf("Recording project-a / work", "since 08:39 UTC", "Updated 02:00:00", "Queue 0 · app $app") },
            StatusCase(
                "not recording omits the since line",
                snapshot(fetchedAt = fetchedAt),
            ) { app -> listOf("Not recording", "Updated 02:00:00", "Queue 0 · app $app") },
            StatusCase(
                "an unsent tap before sending starts shows the tap time",
                snapshot(selection = Selection(meeting, tapAt), pendingTapAt = tapAt, queuedTapCount = 1),
            ) { app -> listOf("Recording project-a / meeting", "since 02:19 UTC", "Saving… sent 02:19:03", "Queue 1 · app $app") },
            StatusCase(
                "an in-flight tap shows the time it was sent",
                snapshot(
                    selection = Selection(meeting, tapAt),
                    pendingTapAt = tapAt,
                    sentAt = Instant.parse("2026-11-09T02:19:04.612Z"),
                    queuedTapCount = 1,
                ),
            ) { app -> listOf("Recording project-a / meeting", "since 02:19 UTC", "Saving… sent 02:19:04", "Queue 1 · app $app") },
            StatusCase(
                "three unanswered taps count three in the queue line",
                snapshot(
                    selection = Selection(meeting, tapAt),
                    pendingTapAt = tapAt,
                    sentAt = Instant.parse("2026-11-09T02:19:04.612Z"),
                    queuedTapCount = 3,
                ),
            ) { app -> listOf("Recording project-a / meeting", "since 02:19 UTC", "Saving… sent 02:19:04", "Queue 3 · app $app") },
            StatusCase(
                "waiting for the network shows the oldest unsent tap time",
                snapshot(
                    selection = Selection(meeting, tapAt),
                    fetchedAt = fetchedAt,
                    outcome = WidgetOutcome.WaitingForNetwork,
                    pendingTapAt = tapAt,
                    oldestUnsentTapAt = tapAt,
                    queuedTapCount = 2,
                ),
            ) { app -> listOf("Recording project-a / meeting", "since 02:19 UTC", "Waiting for network since 02:19:03", "Queue 2 · app $app") },
            StatusCase(
                "a successful save shows the response time and the round trip in seconds",
                snapshot(
                    selection = Selection(meeting, workSince),
                    fetchedAt = Instant.parse("2026-11-09T02:19:05.100Z"),
                    savedAt = Instant.parse("2026-11-09T02:19:05.100Z"),
                    roundTripMillis = 2_300,
                ),
            ) { app -> listOf("Recording project-a / meeting", "since 08:39 UTC", "Saved 02:19:05 (2.3 s)", "Queue 0 · app $app") },
            StatusCase(
                "a four-second round trip keeps one decimal",
                snapshot(
                    selection = Selection(meeting, workSince),
                    fetchedAt = Instant.parse("2026-11-09T02:19:05.100Z"),
                    savedAt = Instant.parse("2026-11-09T02:19:05.100Z"),
                    roundTripMillis = 4_000,
                ),
            ) { app -> listOf("Recording project-a / meeting", "since 08:39 UTC", "Saved 02:19:05 (4.0 s)", "Queue 0 · app $app") },
            StatusCase(
                "a server rejection shows its message",
                snapshot(
                    selection = Selection(work, workSince),
                    fetchedAt = fetchedAt,
                    outcome = WidgetOutcome.Rejected("project not allowed"),
                ),
            ) { app -> listOf("Recording project-a / work", "since 08:39 UTC", "⚠ project not allowed", "Queue 0 · app $app") },
            StatusCase(
                "a refresh that could not connect with nothing pending shows the as-of time",
                snapshot(fetchedAt = fetchedAt, outcome = WidgetOutcome.Unreachable),
            ) { app -> listOf("Not recording", "⚠ Could not connect (showing state as of 02:00 UTC)", "Queue 0 · app $app") },
        )
        cases.forEach { case ->
            assertEquals(
                "case=${case.name}",
                case.expectedLines(versionName).joinToString("\n"),
                factory.statusText(case.snapshot, now),
            )
        }
    }

    @Test
    fun theStatusTextViewIsVisibleOnlyWhileStatusDetailsAreShown() {
        val shown = factory.build(WIDGET_TEST_SETTINGS, WidgetSnapshot.EMPTY, now).apply(context, FrameLayout(context))
        val hidden = factory
            .build(WidgetSettings(WIDGET_TEST_SETTINGS.endpointUrl, WIDGET_TEST_SETTINGS.projects, showStatusDetails = false), WidgetSnapshot.EMPTY, now)
            .apply(context, FrameLayout(context))
        assertEquals(View.VISIBLE, shown.findViewById<TextView>(R.id.status_text).visibility)
        assertEquals(View.GONE, hidden.findViewById<TextView>(R.id.status_text).visibility)
    }
}
