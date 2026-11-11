package com.hiromishikata.timetrackingradio.adapter.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueQueuedTapRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.QueuedTap
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.ui.SettingsActivity
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val ATTEMPTS = 8
private const val MAX_RETRY_DELAY_MILLIS = 60_000L
private const val SLACK_MILLIS = 1_000L
private const val SHORT_WAIT_MILLIS = 5_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetTapRetryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val first = CellKey("project-a", ActivityKind.MEETING)
    private val second = CellKey("project-b", ActivityKind.WORK)
    private lateinit var executor: ExecutorService

    private inner class ProjectNetwork(private val project: String) {
        val failing = AtomicBoolean(true)
        private val failed = AtomicInteger()
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST" && selectedProjectOf(request) == project && failing.get()) {
                failed.incrementAndGet()
                throw UnknownHostException("unreachable.example")
            }
        }

        fun posts(): Int = transport.requests.count { it.method == "POST" && selectedProjectOf(it) == project }

        fun answeredPosts(): Int = posts() - failed.get()
    }

    @Before
    fun setUp() {
        executor = installWorkManagerWithControlledExecutor(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
    }

    @After
    fun tearDown() {
        restoreDefaultWidgetFactories()
        executor.shutdownNow()
    }

    private fun installTransport(transport: EchoSelectionTransport) {
        val factory: (Context) -> WidgetController = {
            WidgetController(it, { transport }, { transport }, publish = { _, _ -> })
        }
        SelectionWidgetProvider.controllerFactory = factory
        WidgetSelectionWorker.controllerFactory = factory
    }

    private fun tapIntent(cell: CellKey) = cellTapIntent(cell.project, cell.activity.code)

    @Test
    fun aTransientFailureIsNextAttemptedWithinSixtySecondsOverManyAttempts() {
        val network = ProjectNetwork(first.project)
        installTransport(network.transport)
        SelectionWidgetProvider().onReceive(context, tapIntent(first))
        meetConstraintsOfAllWidgetWork(context)
        repeat(ATTEMPTS) { index ->
            val attempt = index + 1
            awaitCondition("attempt $attempt to fail and schedule its next attempt", timeoutMillis = 150_000) {
                meetConstraintsOfAllWidgetWork(context)
                val infos = allWidgetWorkInfos(context)
                network.posts() >= attempt &&
                    infos.none { it.state == WorkInfo.State.RUNNING } &&
                    infos.any { !it.state.isFinished && it.nextScheduleTimeMillis > System.currentTimeMillis() }
            }
            val now = System.currentTimeMillis()
            val scheduled = allWidgetWorkInfos(context).filter { !it.state.isFinished && it.nextScheduleTimeMillis != Long.MAX_VALUE }
            assertTrue("attempt $attempt left nothing scheduled", scheduled.isNotEmpty())
            scheduled.forEach { info ->
                val delay = info.nextScheduleTimeMillis - now
                assertTrue(
                    "after attempt $attempt the next attempt is $delay ms away, over the $MAX_RETRY_DELAY_MILLIS ms bound",
                    delay <= MAX_RETRY_DELAY_MILLIS + SLACK_MILLIS,
                )
            }
            meetConstraintsAndDelaysOfAllWidgetWork(context)
        }
        assertTrue("attempts made: ${network.posts()}", network.posts() >= ATTEMPTS)
    }

    @Test
    fun aTransientFailureRecordsItsRetryScheduleInTheDiagnosticLog() {
        val network = ProjectNetwork(first.project)
        installTransport(network.transport)
        SelectionWidgetProvider().onReceive(context, tapIntent(first))
        meetConstraintsOfAllWidgetWork(context)
        awaitCondition("the retry schedule is recorded") {
            meetConstraintsOfAllWidgetWork(context)
            widgetDiagnosticLog(context).readAll().any { entry ->
                entry.type == DiagnosticEventType.RETRY_SCHEDULED &&
                    entry.detail.contains("kind=tap_queue") &&
                    entry.detail.contains("attempt=1") &&
                    entry.detail.contains("next_delay_seconds=60")
            }
        }
    }

    @Test
    fun aLaterTapIsSentWhileAnEarlierTapWaitsForItsRetry() {
        val network = ProjectNetwork(first.project)
        installTransport(network.transport)
        SelectionWidgetProvider().onReceive(context, tapIntent(first))
        SelectionWidgetProvider().onReceive(context, tapIntent(second))
        awaitCondition("the later tap is sent while the earlier tap waits") {
            meetConstraintsOfAllWidgetWork(context)
            network.transport.requests.any { it.method == "POST" && selectedProjectOf(it) == second.project }
        }
        assertEquals("the earlier tap is still unanswered", 0, network.answeredPosts())
    }

    @Test
    fun aNewerTapIsSentAfterTheOlderPendingTapEvenWhenTheNetworkReturnsTogether() {
        val failing = AtomicBoolean(true)
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST" && selectedProjectOf(request) == first.project && failing.get()) {
                throw UnknownHostException("unreachable.example")
            }
        }
        installTransport(transport)
        SelectionWidgetProvider().onReceive(context, tapIntent(first))
        meetConstraintsOfAllWidgetWork(context)
        awaitCondition("the older tap fails once") { transport.requests.count { it.method == "POST" } >= 1 }
        failing.set(false)
        val known = pendingWidgetWorkIds(context)
        SelectionWidgetProvider().onReceive(context, tapIntent(second))
        awaitCondition("both taps are answered") {
            meetConstraintsOfWidgetWorkNotIn(context, known)
            transport.requests.count { it.method == "POST" && selectedProjectOf(it) == second.project } >= 1
        }
        assertEquals(
            listOf(first.project, first.project, second.project),
            transport.requests.filter { it.method == "POST" }.map { selectedProjectOf(it) },
        )
    }

    @Test
    fun aNewTapSendsThePendingTapWithoutWaitingForItsRetryDelay() {
        val network = ProjectNetwork(first.project)
        installTransport(network.transport)
        SelectionWidgetProvider().onReceive(context, tapIntent(first))
        meetConstraintsOfAllWidgetWork(context)
        awaitCondition("the first tap fails once") { network.posts() >= 1 }
        network.failing.set(false)
        val known = pendingWidgetWorkIds(context)
        SelectionWidgetProvider().onReceive(context, tapIntent(second))
        awaitCondition("the pending tap is sent when a new tap arrives", SHORT_WAIT_MILLIS) {
            meetConstraintsOfWidgetWorkNotIn(context, known)
            network.answeredPosts() >= 1 && network.transport.requests.any { it.method == "POST" && selectedProjectOf(it) == second.project }
        }
    }

    @Test
    fun aWidgetUpdateSendsThePendingTapWithoutWaitingForItsRetryDelay() {
        val network = ProjectNetwork(first.project)
        installTransport(network.transport)
        SelectionWidgetProvider().onReceive(context, tapIntent(first))
        meetConstraintsOfAllWidgetWork(context)
        awaitCondition("the first tap fails once") { network.posts() >= 1 }
        network.failing.set(false)
        val known = pendingWidgetWorkIds(context)
        SelectionWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), IntArray(0))
        awaitCondition("the pending tap is sent when the widget refreshes", SHORT_WAIT_MILLIS) {
            meetConstraintsOfWidgetWorkNotIn(context, known)
            network.answeredPosts() >= 1
        }
    }

    @Test
    fun openingTheSettingsScreenSendsThePendingTapWithoutWaitingForItsRetryDelay() {
        val network = ProjectNetwork(first.project)
        installTransport(network.transport)
        SelectionWidgetProvider().onReceive(context, tapIntent(first))
        meetConstraintsOfAllWidgetWork(context)
        awaitCondition("the first tap fails once") { network.posts() >= 1 }
        network.failing.set(false)
        val known = pendingWidgetWorkIds(context)
        Robolectric.buildActivity(SettingsActivity::class.java).create()
        awaitCondition("the pending tap is sent when the settings screen opens", SHORT_WAIT_MILLIS) {
            meetConstraintsOfWidgetWorkNotIn(context, known)
            network.answeredPosts() >= 1
        }
    }

    @Test
    fun aTapBackedOffByTheEarlierVersionIsSentRightAfterTheAppStarts() {
        assertTheBackedOffTapIsSentAfter {
            TestListenableWorkerBuilder<WidgetAppStartWorker>(context).build().startWork().get()
        }
    }

    @Test
    fun aTapBackedOffByTheEarlierVersionIsSentRightAfterTheAppIsUpdated() {
        assertTheBackedOffTapIsSentAfter {
            AppUpdatedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        }
    }

    private fun assertTheBackedOffTapIsSentAfter(trigger: () -> Unit) {
        val network = ProjectNetwork(first.project).apply { failing.set(false) }
        installTransport(network.transport)
        val backedOff = OneTimeWorkRequestBuilder<WidgetSelectionWorker>()
            .setInputData(
                workDataOf(
                    "project" to first.project,
                    "activity" to first.activity.code,
                    "tappedAt" to TEST_TAPPED_AT_TEXT,
                    "requestKind" to "select",
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(5, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetSelectionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            backedOff,
        )
        KeyValueQueuedTapRepository(widgetPreferences(context)).write(
            listOf(QueuedTap(first, TEST_TAPPED_AT, SelectionRequest.Select(first), workEnqueued = true, waiting = true, workId = backedOff.id)),
        )
        trigger()
        awaitCondition("the backed-off tap is sent after the app starts", 10_000) {
            meetConstraintsOfAllWidgetWork(context)
            network.answeredPosts() >= 1
        }
        val sentTappedAts = network.transport.requests
            .filter { it.method == "POST" }
            .map { postedBodyOf(it).getValue("tappedAt").jsonPrimitive.content }
        assertEquals(listOf(TEST_TAPPED_AT_TEXT), sentTappedAts)
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfoById(backedOff.id).get()?.state)
    }
}
