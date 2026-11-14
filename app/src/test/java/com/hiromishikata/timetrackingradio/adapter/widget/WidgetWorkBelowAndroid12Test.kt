package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = TestApplication::class)
class WidgetWorkBelowAndroid12Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)

    private fun acceptedTapFor(cell: CellKey): QueuedTapAccept =
        WidgetController(context, publish = { _, _ -> }).acceptQueued(cell)

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
    fun aTapIsSentOnAndroidBelowTwelve() {
        val transport = EchoSelectionTransport()
        val rendered = mutableListOf<WidgetSnapshot>()
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot })
        }
        WidgetWorkScheduler.enqueueWorkRequest(context, acceptedTapFor(meeting).workRequest)
        val work = advanceUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertEquals("work state", WorkInfo.State.SUCCEEDED, work.state)
        assertEquals(
            listOf("project-a"),
            transport.requests.filter { it.method == "POST" }.map { selectedProjectOf(it) },
        )
    }

    @Test
    fun aTapWhoseNetworkIsUnavailableIsRetriedAndSentOnceWithItsOriginalTappedAt() {
        val clock = AtomicReference(TEST_TAPPED_AT)
        val postAttempts = AtomicInteger()
        val repliedPosts = AtomicInteger()
        val transport = EchoSelectionTransport { request ->
            if (request.method == "POST") {
                if (postAttempts.incrementAndGet() == 1) {
                    clock.set(TEST_TAPPED_AT.plusSeconds(30))
                    throw UnknownHostException("unreachable.example")
                }
                repliedPosts.incrementAndGet()
            }
        }
        val factory: (Context) -> WidgetController = {
            WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { clock.get() })
        }
        SelectionWidgetProvider.controllerFactory = factory
        WidgetSelectionWorker.controllerFactory = factory

        SelectionCellTapReceiver().onReceive(context, cellTapIntent(meeting.project, meeting.activity.code))
        driveAllWidgetWorkToFinish(context)

        assertEquals("work state", WorkInfo.State.SUCCEEDED, uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).single().state)
        val posts = transport.requests.filter { it.method == "POST" }
        assertEquals("POST attempts, the failed one and the retry", 2, posts.size)
        assertEquals("POSTs that received a reply", 1, repliedPosts.get())
        assertEquals(
            listOf(TEST_TAPPED_AT_TEXT, TEST_TAPPED_AT_TEXT),
            posts.map { postedBodyOf(it).getValue("tappedAt").jsonPrimitive.content },
        )
        assertEquals(meeting, widgetSnapshots(context).read().selection?.cell)
    }

    @Test
    fun aDiagnosticsUploadSucceedsOnAndroidBelowTwelve() {
        WidgetWorkScheduler.enqueueDiagnosticsUpload(context)
        val work = advanceUniqueWork(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME)
        assertEquals("work state", WorkInfo.State.SUCCEEDED, work.state)
    }

    private fun advanceUniqueWork(context: Context, uniqueWorkName: String): WorkInfo {
        val deadline = System.currentTimeMillis() + 5_000
        while (uniqueWorkInfos(context, uniqueWorkName).isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "no work was enqueued under $uniqueWorkName" }
            Thread.sleep(10)
        }
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        uniqueWorkInfos(context, uniqueWorkName).forEach { info ->
            driver.setAllConstraintsMet(info.id)
            driver.setInitialDelayMet(info.id)
        }
        awaitWorkFinished(context, uniqueWorkName)
        return uniqueWorkInfos(context, uniqueWorkName).single()
    }
}
