package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.UnconfirmedTap
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UnsentTapDisplayTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val firstTap = Instant.parse("2026-11-09T02:19:03.112Z")
    private val secondTap = Instant.parse("2026-11-09T02:20:10.000Z")
    private val now = Instant.parse("2026-11-09T02:21:00Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val projectBWork = CellKey("project-b", ActivityKind.WORK)
    private val factory = SelectionWidgetRemoteViewsFactory(context)
    private val versionName: String
        get() = checkNotNull(context.packageManager.getPackageInfo(context.packageName, 0).versionName)

    private data class StatusCase(val name: String, val snapshot: WidgetSnapshot, val expected: (String) -> List<String>)

    private fun snapshot(
        selection: Selection?,
        outcome: WidgetOutcome = WidgetOutcome.Settled,
        pendingTapAt: Instant? = null,
        sentAt: Instant? = null,
        oldestUnsentTapAt: Instant? = null,
        savedAt: Instant? = null,
        roundTripMillis: Long? = null,
        queuedTapCount: Int = 0,
        unsent: UnconfirmedTap? = null,
        dataSaverRestricted: Boolean = false,
    ) = WidgetSnapshot(
        selection = selection,
        fetchedAt = since,
        outcome = outcome,
        sending = null,
        statusRequest = null,
        pendingTapAt = pendingTapAt,
        sentAt = sentAt,
        oldestUnsentTapAt = oldestUnsentTapAt,
        savedAt = savedAt,
        roundTripMillis = roundTripMillis,
        queuedTapCount = queuedTapCount,
        latestUnconfirmedTap = unsent,
        dataSaverRestricted = dataSaverRestricted,
    )

    @Test
    fun theStatusTextShowsTheUnconfirmedTapInItsFourLines() {
        val cases = listOf(
            StatusCase(
                "a select that is not sent yet is shown as not saved, with its tap time",
                snapshot(Selection(projectBWork, since), pendingTapAt = firstTap, queuedTapCount = 1, unsent = UnconfirmedTap(SelectionRequest.Select(meeting), firstTap)),
            ) { app -> listOf("Not saved: project-a / meeting", "tapped 02:19 UTC", "Saving… sent 02:19:03", "Queue 1 · app $app") },
            StatusCase(
                "a clear that is not sent yet is shown as not saved stop recording",
                snapshot(Selection(meeting, since), pendingTapAt = firstTap, queuedTapCount = 1, unsent = UnconfirmedTap(SelectionRequest.Clear, firstTap)),
            ) { app -> listOf("Not saved: stop recording", "tapped 02:19 UTC", "Saving… sent 02:19:03", "Queue 1 · app $app") },
            StatusCase(
                "several queued taps show the latest tap and the oldest waiting time",
                snapshot(
                    Selection(work, since),
                    outcome = WidgetOutcome.WaitingForNetwork,
                    pendingTapAt = firstTap,
                    oldestUnsentTapAt = firstTap,
                    queuedTapCount = 2,
                    unsent = UnconfirmedTap(SelectionRequest.Select(projectBWork), secondTap),
                ),
            ) { app -> listOf("Not saved: project-b / work", "tapped 02:20 UTC", "⚠ Waiting for network since 02:19:03", "Queue 2 · app $app") },
            StatusCase(
                "a waiting tap under data saver shows the data saver line",
                snapshot(
                    Selection(work, since),
                    outcome = WidgetOutcome.WaitingForNetwork,
                    pendingTapAt = firstTap,
                    oldestUnsentTapAt = firstTap,
                    queuedTapCount = 1,
                    unsent = UnconfirmedTap(SelectionRequest.Select(meeting), firstTap),
                    dataSaverRestricted = true,
                ),
            ) { app -> listOf("Not saved: project-a / meeting", "tapped 02:19 UTC", "⚠ Data Saver: unsent since 02:19:03", "Queue 1 · app $app") },
            StatusCase(
                "an in-flight head shows its send time while a later tap waits",
                snapshot(
                    Selection(work, since),
                    pendingTapAt = firstTap,
                    sentAt = Instant.parse("2026-11-09T02:19:04.612Z"),
                    queuedTapCount = 2,
                    unsent = UnconfirmedTap(SelectionRequest.Select(meeting), secondTap),
                ),
            ) { app -> listOf("Not saved: project-a / meeting", "tapped 02:20 UTC", "Saving… sent 02:19:04", "Queue 2 · app $app") },
            StatusCase(
                "a rejection shows its message while a tap is still unsent",
                snapshot(
                    Selection(work, since),
                    outcome = WidgetOutcome.Rejected("wrong key"),
                    pendingTapAt = secondTap,
                    queuedTapCount = 1,
                    unsent = UnconfirmedTap(SelectionRequest.Select(meeting), secondTap),
                ),
            ) { app -> listOf("Not saved: project-a / meeting", "tapped 02:20 UTC", "⚠ wrong key", "Queue 1 · app $app") },
            StatusCase(
                "a rejection with nothing unsent shows the server selection and its message",
                snapshot(Selection(projectBWork, since), outcome = WidgetOutcome.Rejected("project not allowed")),
            ) { app -> listOf("Recording project-b / work", "since 08:39 UTC", "⚠ project not allowed", "Queue 0 · app $app") },
            StatusCase(
                "a confirmation replaces the projection with the server selection and its save time",
                snapshot(
                    Selection(meeting, Instant.parse("2026-11-09T02:19:00Z")),
                    savedAt = Instant.parse("2026-11-09T02:19:05.100Z"),
                    roundTripMillis = 2_300,
                ),
            ) { app -> listOf("Recording project-a / meeting", "since 02:19 UTC", "Saved 02:19:05 (2.3 s)", "Queue 0 · app $app") },
        )
        cases.forEach { case ->
            assertEquals("case=${case.name}", case.expected(versionName), factory.statusText(case.snapshot, now).lines())
        }
    }

    private fun resetWith(confirmed: Selection?) {
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        KeyValueWidgetSettingsRepository(store).write(WIDGET_TEST_SETTINGS)
        KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(confirmed, since, WidgetOutcome.Settled, null, null))
    }

    private fun controllerRendering(
        rendered: MutableList<WidgetSnapshot>,
        clock: () -> Instant,
        dataSaver: Boolean = false,
        transport: HttpTransport = ForbiddenTransport(),
    ) = WidgetController(
        context = context,
        transportFactory = { transport },
        uploadTransportFactory = { transport },
        publish = { _, snapshot -> rendered += snapshot },
        clock = clock,
        dataSaverRestricted = { dataSaver },
    )

    @Test
    fun twoQueuedTapsDashOnlyTheLatestAndKeepTheConfirmedSelectionFilled() {
        resetWith(Selection(projectBWork, since))
        var tappedNow = firstTap
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = controllerRendering(rendered, clock = { tappedNow })
        controller.acceptQueued(meeting)
        tappedNow = secondTap
        controller.acceptQueued(work)
        controller.markWaiting(meeting, firstTap)
        val latest = rendered.last()
        assertEquals(R.drawable.cell_unselected, renderedCellDrawableResId(context, latest, meeting))
        assertEquals(R.drawable.cell_unsent, renderedCellDrawableResId(context, latest, work))
        assertEquals(R.drawable.cell_selected, renderedCellDrawableResId(context, latest, projectBWork))
        assertEquals(
            listOf("Not saved: project-a / work", "tapped 02:20 UTC", "⚠ Waiting for network since 02:19:03", "Queue 2 · app $versionName"),
            factory.statusText(latest, now).lines(),
        )
    }

    @Test
    fun aDataSaverRestrictionChangesTheWaitingLineOfTheSameTap() {
        resetWith(Selection(projectBWork, since))
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = controllerRendering(rendered, clock = { firstTap }, dataSaver = true)
        controller.acceptQueued(meeting)
        controller.markWaiting(meeting, firstTap)
        assertEquals("⚠ Data Saver: unsent since 02:19:03", factory.statusText(rendered.last(), now).lines()[2])
    }

    @Test
    fun aSavedOlderTapShowsTheServerSelectionAndKeepsTheNewerTapDashed() {
        resetWith(null)
        val okBody = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, okBody) })))
        var tappedNow = firstTap
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = controllerRendering(rendered, clock = { tappedNow }, transport = transport)
        val first = controller.acceptQueued(meeting)
        tappedNow = secondTap
        controller.acceptQueued(work)
        runBlocking { controller.performTap(meeting, first.accepted.request, first.accepted.tappedAt) }
        val afterSave = rendered.last()
        assertEquals(R.drawable.cell_selected, renderedCellDrawableResId(context, afterSave, meeting))
        assertEquals(R.drawable.cell_unsent, renderedCellDrawableResId(context, afterSave, work))
        assertEquals("Not saved: project-a / work", factory.statusText(afterSave, now).lines()[0])
    }
}
