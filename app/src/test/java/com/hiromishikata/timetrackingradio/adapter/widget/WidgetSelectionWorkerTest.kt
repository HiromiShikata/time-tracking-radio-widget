package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.HttpRequest
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetSelectionWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val rendered = mutableListOf<WidgetSnapshot>()
    private lateinit var transport: EchoSelectionTransport

    private data class RunCase(val name: String, val input: Data, val expectedMethods: List<String>)

    private val tapInput = workDataOf(
        WidgetSelectionWorker.KEY_PROJECT to "project-a",
        WidgetSelectionWorker.KEY_ACTIVITY to "meeting",
        WidgetSelectionWorker.KEY_TAPPED_AT to TEST_TAPPED_AT_TEXT,
    )

    @Before
    fun setUp() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
        transport = EchoSelectionTransport()
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot }) }
    }

    @After
    fun tearDown() {
        restoreDefaultWidgetFactories()
    }

    private fun runWorker(input: Data): ListenableWorker.Result =
        TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(input).build().startWork().get()

    @Test
    fun eachInputRunsItsSelectionCallAndRendersTheResult() {
        val cases = listOf(
            RunCase("a tap sends one selection post", tapInput, listOf("POST")),
            RunCase("a refresh only reads the selection", Data.EMPTY, listOf("GET")),
        )
        cases.forEach { case ->
            transport = EchoSelectionTransport()
            rendered.clear()
            val result = runWorker(case.input)
            assertTrue("case=${case.name}", result is ListenableWorker.Result.Success)
            assertEquals("case=${case.name}", case.expectedMethods, transport.requests.map { it.method })
            val stored = widgetSnapshots(context).read()
            assertNull("case=${case.name}", stored.sending)
            assertEquals("case=${case.name}", stored, rendered.last())
        }
    }

    @Test
    fun aTapStoresTheSelectionTheServerReturns() {
        runWorker(tapInput)
        assertEquals(Selection(meeting, since), widgetSnapshots(context).read().selection)
    }

    @Test
    fun aSucceededTapEnqueuesTheDiagnosticsUploadUnderItsOwnName() {
        runWorker(tapInput)
        assertEquals(1, uniqueWorkInfos(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun aFailedTapStillEnqueuesTheDiagnosticsUpload() {
        transport = EchoSelectionTransport { throw IllegalStateException("unexpected") }
        runWorker(tapInput)
        assertTrue(
            "no diagnostics upload was enqueued after the failed tap",
            uniqueWorkInfos(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME).isNotEmpty(),
        )
    }

    @Test
    fun aStoppedTapDoesNotEnqueueTheDiagnosticsUpload() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport = EchoSelectionTransport {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val worker = TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(tapInput).build()
        val outcome = worker.startWork()
        try {
            assertTrue("the selection request started", started.await(5, TimeUnit.SECONDS))
            worker.onStopped()
        } finally {
            release.countDown()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!outcome.isDone) {
            check(System.currentTimeMillis() < deadline) { "the stopped work did not finish" }
            Thread.sleep(10)
        }
        assertTrue(
            "a stopped tap enqueued a diagnostics upload",
            uniqueWorkInfos(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME).isEmpty(),
        )
    }

    @Test
    fun anUnexpectedFailureOfTheSelectionCallClearsTheSendingStateAndSucceedsSoChainedWorkStillRuns() {
        transport = EchoSelectionTransport { throw IllegalStateException("unexpected") }
        val result = runWorker(tapInput)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertTrue("the sending state was rendered first", rendered.any { it.sending != null })
        assertNull(widgetSnapshots(context).read().sending)
        assertNull("the last rendered screen still shows a sending cell", rendered.last().sending)
    }

    private data class PostBodyCase(
        val name: String,
        val previous: Selection?,
        val expectedProject: String?,
        val expectedActivity: String?,
    )

    @Test
    fun aTapPostNamesTheTappedCellAndAClearPostNamesNoCell() {
        val cases = listOf(
            PostBodyCase("a tap on an unselected cell names that cell", null, "project-a", "meeting"),
            PostBodyCase("a tap on the selected cell is a clear", Selection(meeting, since), null, null),
        )
        cases.forEach { case ->
            widgetSnapshots(context).write(WidgetSnapshot(case.previous, since, WidgetOutcome.Settled, null))
            transport = EchoSelectionTransport()
            val result = runWorker(tapInput)
            assertTrue("case=${case.name} result=$result", result is ListenableWorker.Result.Success)
            val body = postedBodyOf(transport.requests.single { it.method == "POST" })
            assertEquals("case=${case.name}", case.expectedProject, body.getValue("project").jsonPrimitive.contentOrNull)
            assertEquals("case=${case.name}", case.expectedActivity, body.getValue("activity").jsonPrimitive.contentOrNull)
        }
    }

    @Test
    fun anNgResponseFinalisesTheTapWithItsMessageAndIsSentOnce() {
        val posts = CopyOnWriteArrayList<HttpRequest>()
        val ngTransport = object : HttpTransport {
            override fun send(request: HttpRequest): HttpResponse {
                posts += request
                return HttpResponse(200, null, """{"result":"ng","message":"wrong key"}""")
            }
        }
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { ngTransport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot })
        }
        val result = runWorker(tapInput)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(1, posts.count { it.method == "POST" })
        val stored = widgetSnapshots(context).read()
        assertEquals(WidgetOutcome.Rejected("wrong key"), stored.outcome)
        assertNull(stored.sending)
        assertNull(rendered.last().sending)
    }

    private data class FailureCase(val name: String, val failure: Throwable, val expectedResult: Class<out ListenableWorker.Result>)

    @Test
    fun aNetworkLevelFailureOfATapRequestsARetryAndAnyOtherFailureDoesNot() {
        val cases = listOf(
            FailureCase("unknown host", UnknownHostException("unreachable.example"), ListenableWorker.Result.Retry::class.java),
            FailureCase("timeout", SocketTimeoutException("timeout"), ListenableWorker.Result.Retry::class.java),
            FailureCase("connection refused", ConnectException("Connection refused"), ListenableWorker.Result.Retry::class.java),
            FailureCase("connection aborted", SocketException("Software caused connection abort"), ListenableWorker.Result.Retry::class.java),
            FailureCase("TLS failure", SSLHandshakeException("handshake failed"), ListenableWorker.Result.Retry::class.java),
            FailureCase("unexpected failure", IllegalStateException("unexpected"), ListenableWorker.Result.Success::class.java),
        )
        cases.forEach { case ->
            transport = EchoSelectionTransport { throw case.failure }
            val result = runWorker(tapInput)
            assertEquals("case=${case.name} result=$result", case.expectedResult, result.javaClass)
        }
    }

    @Test
    fun aTapWhoseNetworkAttemptFailsReturnsToTheWaitingStatusWithItsRingUntilTheNextAttempt() {
        transport = EchoSelectionTransport { throw UnknownHostException("unreachable.example") }
        val result = runWorker(tapInput)
        assertTrue("result=$result", result is ListenableWorker.Result.Retry)
        val waiting = rendered.last()
        assertEquals("the ring stays on the waiting cell", SelectionRequest.Select(meeting), waiting.sending)
        assertEquals(R.drawable.cell_sending, renderedCellDrawableResId(context, waiting, meeting))
        val status = renderedStatusText(context, waiting)
        assertTrue("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
    }

    @Test
    fun aStoppedWorkClearsTheSendingState() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        widgetSnapshots(context).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, SelectionRequest.Select(meeting)))
        val worker = TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(tapInput).build()
        worker.onStopped()
        assertNull(widgetSnapshots(context).read().sending)
        assertNull(rendered.last().sending)
        assertEquals(Selection(meeting, since), widgetSnapshots(context).read().selection)
    }
}
