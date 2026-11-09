package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.WorkInfo
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CellVisual
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.resolveCellVisual
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetWorkSchedulerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val first = CellKey("project-a", ActivityKind.MEETING)
    private val second = CellKey("project-b", ActivityKind.WORK)
    private lateinit var executor: ExecutorService

    private fun acceptedTapAt(cell: CellKey): Instant =
        WidgetController(context, publish = { _, _ -> }).acceptTap(cell, workEnqueued = true)

    @Before
    fun setUp() {
        executor = installWorkManagerWithControlledExecutor(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
    }

    @After
    fun tearDown() {
        try {
            driveUniqueWorkToFinish(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
            awaitWorkFinished(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME)
        } finally {
            restoreDefaultWidgetFactories()
            executor.shutdownNow()
        }
    }

    @Test
    fun enqueueTapPlacesItsWorkUnderTheSelectionUniqueName() {
        WidgetWorkScheduler.enqueueTap(context, first, acceptedTapAt(first))
        meetConstraintsOnceQueued(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertEquals(1, uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun enqueueRefreshPlacesItsWorkUnderTheSelectionUniqueName() {
        WidgetWorkScheduler.enqueueRefresh(context)
        assertEquals(1, uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun enqueueDiagnosticsUploadPlacesItsWorkUnderTheUploadUniqueName() {
        WidgetWorkScheduler.enqueueDiagnosticsUpload(context)
        assertEquals(1, uniqueWorkInfos(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun twoConsecutiveTapsRunInOrderAndTheLastTappedCellIsStored() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateFirstSelection = AtomicBoolean(true)
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST" && gateFirstSelection.getAndSet(false)) {
                started.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "the first selection was never released by the test" }
            }
        }
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { transport }, publish = { _, _ -> }) }
        try {
            WidgetWorkScheduler.enqueueTap(context, first, acceptedTapAt(first))
            meetConstraintsOnceQueued(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
            assertTrue("the first tap's selection call started", started.await(30, TimeUnit.SECONDS))
            WidgetWorkScheduler.enqueueTap(context, second, acceptedTapAt(second))
            val queued = awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the second tap being queued") { it.size == 2 }
            meetAllConstraints(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
            assertEquals(setOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), queued.map { it.state }.toSet())
        } finally {
            release.countDown()
        }
        awaitWorkFinished(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertEquals(
            listOf("project-a", "project-b"),
            transport.requests.filter { it.method == "POST" }.map { selectedProjectOf(it) },
        )
        assertEquals(second, widgetSnapshots(context).read().selection?.cell)
    }

    @Test
    fun aTapWhoseSelectionCallThrowsDoesNotDropTheTapQueuedAfterIt() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST" && selectedProjectOf(request) == first.project) {
                started.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "the first selection was never released by the test" }
                throw IllegalStateException("unexpected")
            }
        }
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { transport }, publish = { _, _ -> }) }
        val ids: Set<UUID>
        try {
            WidgetWorkScheduler.enqueueTap(context, first, acceptedTapAt(first))
            meetConstraintsOnceQueued(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
            assertTrue("the first tap's selection call started", started.await(30, TimeUnit.SECONDS))
            WidgetWorkScheduler.enqueueTap(context, second, acceptedTapAt(second))
            val queued = awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the second tap being queued") { it.size == 2 }
            meetAllConstraints(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
            ids = queued.map { it.id }.toSet()
        } finally {
            release.countDown()
        }
        awaitAllSucceeded(ids)
        assertEquals(
            listOf("project-a", "project-b"),
            transport.requests.filter { it.method == "POST" }.map { selectedProjectOf(it) },
        )
        assertEquals(second, widgetSnapshots(context).read().selection?.cell)
    }

    @Test
    fun aTapIsNotStartedWhileNoNetworkIsAvailable() {
        val transport = EchoSelectionTransport()
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { transport }, publish = { _, _ -> }) }
        SelectionWidgetProvider().onReceive(context, cellTapIntent(first.project, first.activity.code))
        awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the tap being queued") { it.isNotEmpty() }
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val states = uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).map { it.state }
            assertEquals("the states of the tap while the host has no network", listOf(WorkInfo.State.ENQUEUED), states)
            assertTrue("the tap was posted without a network", transport.requests.none { it.method == "POST" })
            Thread.sleep(50)
        }
    }

    @Test
    fun aLaterTapWaitsBehindATapThatIsRetrying() {
        val failedFirstAttempt = CountDownLatch(1)
        val failFirstAttempt = AtomicBoolean(true)
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST" && selectedProjectOf(request) == first.project && failFirstAttempt.getAndSet(false)) {
                failedFirstAttempt.countDown()
                throw UnknownHostException("unreachable.example")
            }
        }
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { transport }, publish = { _, _ -> }) }
        SelectionWidgetProvider().onReceive(context, cellTapIntent(first.project, first.activity.code))
        SelectionWidgetProvider().onReceive(context, cellTapIntent(second.project, second.activity.code))
        meetConstraintsOnceQueued(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertTrue("the first attempt was made", failedFirstAttempt.await(30, TimeUnit.SECONDS))
        awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the first tap waiting for a retry") { infos ->
            infos.size == 2 && infos.map { it.state }.toSet() == setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)
        }
        assertTrue(
            "the later tap was posted while the earlier tap was waiting",
            transport.requests.none { it.method == "POST" && selectedProjectOf(it) == second.project },
        )
        driveUniqueWorkToFinish(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertEquals(
            listOf("project-a", "project-a", "project-b"),
            transport.requests.filter { it.method == "POST" }.map { selectedProjectOf(it) },
        )
    }

    @Test
    fun aTapWaitingForTheNetworkKeepsItsRingAndShowsTheWaitingStatusUntilItIsSent() {
        val failedFirstAttempt = CountDownLatch(1)
        val failFirstAttempt = AtomicBoolean(true)
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST" && failFirstAttempt.getAndSet(false)) {
                failedFirstAttempt.countDown()
                throw UnknownHostException("unreachable.example")
            }
        }
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val factory: (Context) -> WidgetController = {
            WidgetController(it, { transport }, { transport }, publish = { _, snapshot -> rendered += snapshot })
        }
        SelectionWidgetProvider.controllerFactory = factory
        WidgetSelectionWorker.controllerFactory = factory
        SelectionWidgetProvider().onReceive(context, cellTapIntent(first.project, first.activity.code))
        meetConstraintsOnceQueued(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertTrue("the first attempt was made", failedFirstAttempt.await(30, TimeUnit.SECONDS))
        awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the tap waiting for a retry") { infos ->
            infos.single().state == WorkInfo.State.ENQUEUED
        }
        val waiting = rendered.last()
        assertEquals("the ring stays on the waiting cell", SelectionRequest.Select(first), waiting.sending)
        assertEquals(CellVisual.SENDING, resolveCellVisual(first, waiting.selection, waiting.sending))
        assertEquals(R.drawable.cell_sending, renderedCellDrawableResId(context, waiting, first))
        val waitingStatus = renderedStatusText(context, waiting)
        assertTrue("status=$waitingStatus", WAITING_FOR_NETWORK_TEXT.containsMatchIn(waitingStatus))

        driveUniqueWorkToFinish(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        val sent = rendered.last()
        assertNull("the ring is gone once the tap is sent", sent.sending)
        val sentStatus = renderedStatusText(context, sent)
        assertFalse("status=$sentStatus", WAITING_FOR_NETWORK_TEXT.containsMatchIn(sentStatus))
    }

    @Test
    fun aPendingRetryKeepsItsRingPastTheTenMinuteAgeBound() {
        val clock = AtomicReference(TEST_TAPPED_AT)
        val failedFirstAttempt = CountDownLatch(1)
        val failFirstAttempt = AtomicBoolean(true)
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST" && failFirstAttempt.getAndSet(false)) {
                failedFirstAttempt.countDown()
                throw UnknownHostException("unreachable.example")
            }
        }
        val factory: (Context) -> WidgetController = {
            WidgetController(it, { transport }, { transport }, publish = { _, _ -> }, clock = { clock.get() })
        }
        SelectionWidgetProvider.controllerFactory = factory
        WidgetSelectionWorker.controllerFactory = factory
        SelectionWidgetProvider().onReceive(context, cellTapIntent(first.project, first.activity.code))
        meetConstraintsOnceQueued(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertTrue("the first attempt was made", failedFirstAttempt.await(30, TimeUnit.SECONDS))
        awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the tap waiting for a retry") { infos ->
            infos.single().state == WorkInfo.State.ENQUEUED
        }
        clock.set(TEST_TAPPED_AT.plusSeconds(11 * 60))
        val rendered = mutableListOf<WidgetSnapshot>()
        WidgetController(context, { transport }, { transport }, publish = { _, snapshot -> rendered += snapshot }, clock = { clock.get() })
            .render()
        assertEquals("the pending tap still holds its ring", SelectionRequest.Select(first), rendered.last().sending)
    }

    private fun awaitAllSucceeded(ids: Set<UUID>) {
        val succeeded = mutableSetOf<UUID>()
        awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "both taps succeeding") { infos ->
            infos.filter { it.state == WorkInfo.State.SUCCEEDED }.forEach { succeeded += it.id }
            succeeded.containsAll(ids)
        }
    }
}
