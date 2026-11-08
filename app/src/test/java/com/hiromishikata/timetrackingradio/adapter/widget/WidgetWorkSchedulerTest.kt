package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.WorkInfo
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetWorkSchedulerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val first = CellKey("project-a", ActivityKind.MEETING)
    private val second = CellKey("project-b", ActivityKind.WORK)
    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        executor = installWorkManagerWithControlledExecutor(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
    }

    @After
    fun tearDown() {
        try {
            awaitWorkFinished(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
            awaitWorkFinished(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME)
        } finally {
            restoreDefaultWidgetFactories()
            executor.shutdownNow()
        }
    }

    @Test
    fun enqueueTapPlacesItsWorkUnderTheSelectionUniqueName() {
        WidgetWorkScheduler.enqueueTap(context, first)
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
            WidgetWorkScheduler.enqueueTap(context, first)
            assertTrue("the first tap's selection call started", started.await(30, TimeUnit.SECONDS))
            WidgetWorkScheduler.enqueueTap(context, second)
            val queued = awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the second tap being queued") { it.size == 2 }
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
            WidgetWorkScheduler.enqueueTap(context, first)
            assertTrue("the first tap's selection call started", started.await(30, TimeUnit.SECONDS))
            WidgetWorkScheduler.enqueueTap(context, second)
            val queued = awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "the second tap being queued") { it.size == 2 }
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

    private fun awaitAllSucceeded(ids: Set<UUID>) {
        val succeeded = mutableSetOf<UUID>()
        awaitUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME, "both taps succeeding") { infos ->
            infos.filter { it.state == WorkInfo.State.SUCCEEDED }.forEach { succeeded += it.id }
            succeeded.containsAll(ids)
        }
    }
}
