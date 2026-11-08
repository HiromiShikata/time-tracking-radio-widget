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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetWorkSchedulerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val first = CellKey("project-a", ActivityKind.MEETING)
    private val second = CellKey("project-b", ActivityKind.WORK)

    @Before
    fun setUp() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
    }

    @After
    fun tearDown() {
        restoreDefaultWidgetFactories()
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
                release.await(10, TimeUnit.SECONDS)
            }
        }
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { transport }) { _, _ -> } }
        val firstEnqueue = thread { WidgetWorkScheduler.enqueueTap(context, first) }
        try {
            assertTrue("the first tap started", started.await(5, TimeUnit.SECONDS))
            WidgetWorkScheduler.enqueueTap(context, second)
            val infos = uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
            assertEquals(2, infos.size)
            assertEquals(setOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), infos.map { it.state }.toSet())
        } finally {
            release.countDown()
            firstEnqueue.join(5_000)
        }
        awaitWorkFinished(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertEquals(
            listOf("project-a", "project-b"),
            transport.requests.filter { it.method == "POST" }.map { selectedProjectOf(it) },
        )
        assertEquals(second, widgetSnapshots(context).read().selection?.cell)
    }
}
