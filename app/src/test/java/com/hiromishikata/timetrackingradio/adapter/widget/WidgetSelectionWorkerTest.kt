package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.HttpRequest
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.google.common.util.concurrent.ListenableFuture
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueQueuedTapRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.update.UpdateCheckScheduler
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import java.io.EOFException
import java.io.IOException
import java.time.Duration
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetSelectionWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val acceptedAt = Instant.parse("2026-11-07T08:00:00Z")
    private val rendered = mutableListOf<WidgetSnapshot>()
    private lateinit var transport: EchoSelectionTransport
    private val legacyCell = CellKey("project-a", ActivityKind.WORK)

    private val SLACK_MILLIS = 1_000L

    private data class RunCase(val name: String, val input: Data, val expectedMethods: List<String>)

    private val updateCheckOnceWorkName = "app-update-check-once"
    private val defaultUpdateCheckTrigger = WidgetSelectionWorker.updateCheckTrigger

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
        WidgetSelectionWorker.updateCheckTrigger = defaultUpdateCheckTrigger
    }

    private fun runWorker(input: Data): ListenableWorker.Result =
        TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(input).build().startWork().get()

    private fun acceptedTapInput(): Data {
        val accepted = WidgetSelectionWorker.controllerFactory(context).acceptQueued(meeting)
        rendered.clear()
        return storedTapInput(context, accepted.workRequest)
    }

    private fun legacyTapInput(): Data = workDataOf(
        WidgetSelectionWorker.KEY_PROJECT to legacyCell.project,
        WidgetSelectionWorker.KEY_ACTIVITY to legacyCell.activity.code,
    )

    @Test
    fun eachInputRunsItsSelectionCallAndRendersTheResult() {
        val tapInput = acceptedTapInput()
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
    fun aSelectionRunEnqueuesAnUpdateCheckOnlyWhenTheLastCheckIsOlderThanSixHours() {
        data class Case(val name: String, val lastCheckedAgo: Duration?, val expectEnqueued: Boolean)
        val cases = listOf(
            Case("no timestamp", null, true),
            Case("seven hours old", Duration.ofHours(7), true),
            Case("one hour old", Duration.ofHours(1), false),
        )
        cases.forEach { case ->
            installWorkManagerForTests(context)
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
            case.lastCheckedAgo?.let { UpdateCheckScheduler.lastCheckRepository(context).write(Instant.now().minus(it)) }
            transport = EchoSelectionTransport()
            val result = runWorker(Data.EMPTY)
            assertTrue("case=${case.name} result=$result", result is ListenableWorker.Result.Success)
            val enqueued = WorkManager.getInstance(context).getWorkInfosForUniqueWork(updateCheckOnceWorkName).get().isNotEmpty()
            assertEquals("case=${case.name}", case.expectEnqueued, enqueued)
        }
    }

    @Test
    fun aTapStoresTheSelectionTheServerReturns() {
        runWorker(acceptedTapInput())
        assertEquals(Selection(meeting, since), widgetSnapshots(context).read().selection)
    }

    @Test
    fun aSucceededTapEnqueuesTheDiagnosticsUploadUnderItsOwnName() {
        runWorker(acceptedTapInput())
        assertEquals(1, uniqueWorkInfos(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun aFailedTapStillEnqueuesTheDiagnosticsUpload() {
        transport = EchoSelectionTransport { throw IllegalStateException("unexpected") }
        runWorker(acceptedTapInput())
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
        val worker = TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(acceptedTapInput()).build()
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
        val result = runWorker(acceptedTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertTrue("the sending state was rendered first", rendered.any { it.sending != null })
        assertNull(widgetSnapshots(context).read().sending)
        assertNull("the last rendered screen still shows a sending cell", rendered.last().sending)
    }

    @Test
    fun aTerminalNonNetworkFailureDiscardsTheTapsEntryAndSucceeds() {
        transport = EchoSelectionTransport { throw IllegalStateException("boom") }
        val result = runWorker(acceptedTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        val last = rendered.last()
        val status = renderedStatusText(context, last)
        assertNull("the stored snapshot still holds the tap's ring", widgetSnapshots(context).read().sending)
        assertNull("the last rendered screen still shows the tap's ring", last.sending)
        assertFalse("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
        assertEquals("the discarded tap projects no selection", R.drawable.cell_unselected, renderedCellDrawableResId(context, last, meeting))
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
            resetWidgetState()
            widgetSnapshots(context).write(WidgetSnapshot(case.previous, since, WidgetOutcome.Settled, null, null))
            transport = EchoSelectionTransport()
            val result = runWorker(acceptedTapInput())
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
        val result = runWorker(acceptedTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(1, posts.count { it.method == "POST" })
        val stored = widgetSnapshots(context).read()
        assertEquals(WidgetOutcome.Rejected("wrong key"), stored.outcome)
        assertNull(stored.sending)
        assertNull(rendered.last().sending)
    }

    private data class FailureCase(val name: String, val failure: Throwable, val expectedResult: Class<out ListenableWorker.Result>)

    @Test
    fun aNetworkLevelFailureOfATapEndsTheWorkWithABoundedRetryAndAnyOtherFailureDoesNot() {
        val cases = listOf(
            FailureCase("unknown host", UnknownHostException("unreachable.example"), ListenableWorker.Result.Success::class.java),
            FailureCase("timeout", SocketTimeoutException("timeout"), ListenableWorker.Result.Success::class.java),
            FailureCase("connection refused", ConnectException("Connection refused"), ListenableWorker.Result.Success::class.java),
            FailureCase("connection aborted", SocketException("Software caused connection abort"), ListenableWorker.Result.Success::class.java),
            FailureCase("TLS failure", SSLHandshakeException("handshake failed"), ListenableWorker.Result.Success::class.java),
            FailureCase("unexpected failure", IllegalStateException("unexpected"), ListenableWorker.Result.Success::class.java),
        )
        cases.forEach { case ->
            resetWidgetState()
            transport = EchoSelectionTransport { throw case.failure }
            val result = runWorker(acceptedTapInput())
            assertEquals("case=${case.name} result=$result", case.expectedResult, result.javaClass)
            if (case.failure !is IllegalStateException) {
                assertTrue(
                    "case=${case.name}: a network-level failure must leave a retry scheduled",
                    uniqueWorkInfos(context, WidgetWorkScheduler.DRAIN_RETRY_UNIQUE_WORK_NAME).any { it.state == WorkInfo.State.ENQUEUED },
                )
            }
        }
    }

    @Test
    fun aTapWhoseNetworkAttemptFailsReturnsToTheWaitingStatusWithItsProjectionUntilTheNextAttempt() {
        transport = EchoSelectionTransport { throw UnknownHostException("unreachable.example") }
        val result = runWorker(acceptedTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        val waiting = rendered.last()
        assertEquals("the sending state stays on the waiting cell", SelectionRequest.Select(meeting), waiting.sending)
        assertEquals(R.drawable.cell_selected, renderedCellDrawableResId(context, waiting, meeting))
        val status = renderedStatusText(context, waiting)
        assertTrue("status=$status", WAITING_FOR_NETWORK_LINE.matches(stateLineOf(status)))
    }

    @Test
    fun aStoppedWorkClearsTheSendingState() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        widgetSnapshots(context).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, SelectionRequest.Select(meeting), null))
        val worker = TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(storedTapInput(context, WidgetWorkScheduler.buildTapRequest(meeting, TEST_TAPPED_AT, SelectionRequest.Select(meeting)))).build()
        worker.onStopped()
        assertNull(widgetSnapshots(context).read().sending)
        assertNull(rendered.last().sending)
        assertEquals(Selection(meeting, since), widgetSnapshots(context).read().selection)
    }

    private data class TransportFailureCase(val name: String, val failure: IOException, val failsOnRedirectFollowUp: Boolean)

    @Test
    fun anIOExceptionFromTheTransportDuringATapRequestRequestsARetryAndKeepsTheTap() {
        val cases = listOf(
            TransportFailureCase("unexpected end of stream", IOException("unexpected end of stream"), false),
            TransportFailureCase("TLS exception", SSLException("handshake"), false),
            TransportFailureCase("end of file", EOFException(), false),
            TransportFailureCase("unexpected end of stream on the redirect follow-up", IOException("unexpected end of stream"), true),
            TransportFailureCase("TLS exception on the redirect follow-up", SSLException("handshake"), true),
            TransportFailureCase("end of file on the redirect follow-up", EOFException(), true),
        )
        cases.forEach { case ->
            resetWidgetState()
            rendered.clear()
            val failing = FailingTapTransport(case.failure, case.failsOnRedirectFollowUp)
            WidgetSelectionWorker.controllerFactory = {
                WidgetController(it, { failing }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot })
            }
            val result = runWorker(acceptedTapInput())
            assertTrue("case=${case.name} result=$result", result is ListenableWorker.Result.Success)
            assertEquals("case=${case.name}", SelectionRequest.Select(meeting), widgetSnapshots(context).read().sending)
            assertEquals("case=${case.name}", SelectionRequest.Select(meeting), rendered.last().sending)
        }
    }

    @Test
    fun aCompleteResponseWithANonJsonBodyIsNotRetriedAndTheTapIsNotResent() {
        resetWidgetState()
        val posts = CopyOnWriteArrayList<HttpRequest>()
        val notJson = object : HttpTransport {
            override fun send(request: HttpRequest): HttpResponse {
                posts += request
                return HttpResponse(200, null, "not json")
            }
        }
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { notJson }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot })
        }
        val result = runWorker(acceptedTapInput())
        assertFalse("result=$result", result is ListenableWorker.Result.Retry)
        assertEquals(1, posts.count { it.method == "POST" })
    }

    @Test
    fun aStoppedWorkDoesNotClearATapThatIsStillPending() {
        resetWidgetState()
        val input = acceptedTapInput()
        val heldWork = WidgetScope.mutex
        runBlocking { heldWork.lock() }
        try {
            val worker = TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(input).build()
            val outcome = worker.startWork()
            worker.onStopped()
            awaitFinished(outcome)
        } finally {
            heldWork.unlock()
        }
        val last = rendered.last()
        val status = renderedStatusText(context, last)
        assertEquals("the stopped tap keeps its sending state", SelectionRequest.Select(meeting), last.sending)
        assertEquals("the stopped tap keeps its projected selection", R.drawable.cell_selected, renderedCellDrawableResId(context, last, meeting))
        assertTrue("status=$status", WAITING_FOR_NETWORK_LINE.matches(stateLineOf(status)))
        assertEquals(SelectionRequest.Select(meeting), widgetSnapshots(context).read().sending)
    }

    @Test
    fun aRetriedTapSendsTheRequestFixedAtAcceptanceOnEveryAttempt() {
        resetWidgetState()
        val flaky = FirstPostFailsTransport(UnknownHostException("unreachable.example"))
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { flaky }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot }, clock = { TEST_TAPPED_AT })
        }
        val input = acceptedTapInput()
        val first = runWorker(input)
        assertTrue("first=$first", first is ListenableWorker.Result.Success)
        widgetSnapshots(context).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, null, null))
        val second = runWorker(input)
        assertTrue("second=$second", second is ListenableWorker.Result.Success)
        assertEquals(listOf(SELECT_MEETING_BODY, SELECT_MEETING_BODY), flaky.postBodies)
    }

    private data class LegacyTapCase(val name: String, val previous: Selection, val expectedBody: String)

    @Test
    fun aLegacyTapWithoutTappedAtResolvesItsRequestFromTheSelectionAtSendTime() {
        val cases = listOf(
            LegacyTapCase(
                "another cell is selected, so the tapped cell is selected",
                Selection(CellKey("project-b", ActivityKind.MEETING), since),
                """{"project":"project-a","activity":"work"}""",
            ),
            LegacyTapCase(
                "the tapped cell is selected, so the tap clears it",
                Selection(legacyCell, since),
                """{"project":null,"activity":null}""",
            ),
        )
        cases.forEach { case ->
            resetWidgetState()
            widgetSnapshots(context).write(WidgetSnapshot(case.previous, since, WidgetOutcome.Settled, null, null))
            transport = EchoSelectionTransport()
            val result = runWorker(legacyTapInput())
            assertTrue("case=${case.name} result=$result", result is ListenableWorker.Result.Success)
            assertEquals(
                "case=${case.name}",
                listOf(case.expectedBody),
                transport.requests.filter { it.method == "POST" }.map { it.body },
            )
        }
    }

    @Test
    fun aNetworkFailureOfALegacyTapRequestsARetryAndKeepsItsRing() {
        resetWidgetState()
        widgetSnapshots(context).write(
            WidgetSnapshot(Selection(CellKey("project-b", ActivityKind.MEETING), since), since, WidgetOutcome.Settled, null, null),
        )
        rendered.clear()
        transport = EchoSelectionTransport { throw UnknownHostException("unreachable.example") }
        val result = runWorker(legacyTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertTrue(
            "a legacy tap's retry must come back within the sixty-second bound",
            uniqueWorkInfos(context, WidgetWorkScheduler.LEGACY_TAP_RETRY_UNIQUE_WORK_NAME).any {
                it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis - System.currentTimeMillis() in 1..(60_000L + SLACK_MILLIS)
            },
        )
        assertEquals(SelectionRequest.Select(legacyCell), widgetSnapshots(context).read().sending)
        assertEquals(SelectionRequest.Select(legacyCell), rendered.last().sending)
    }

    @Test
    fun anIOExceptionFromTheTransportDuringALegacyTapRequestRequestsARetryAndKeepsItsRing() {
        resetWidgetState()
        widgetSnapshots(context).write(
            WidgetSnapshot(Selection(CellKey("project-b", ActivityKind.MEETING), since), since, WidgetOutcome.Settled, null, null),
        )
        rendered.clear()
        transport = EchoSelectionTransport { throw IOException("unexpected end of stream") }
        val result = runWorker(legacyTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(SelectionRequest.Select(legacyCell), widgetSnapshots(context).read().sending)
        assertEquals(SelectionRequest.Select(legacyCell), rendered.last().sending)
    }

    @Test
    fun aFinalTransportFailureOfALegacyTapIsNotRetriedAndShowsUnreachableWithNoRing() {
        resetWidgetState()
        widgetSnapshots(context).write(
            WidgetSnapshot(Selection(CellKey("project-b", ActivityKind.MEETING), since), since, WidgetOutcome.Settled, null, null),
        )
        rendered.clear()
        transport = EchoSelectionTransport { throw ProtocolException("bad") }
        val result = runWorker(legacyTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(1, transport.requests.count { it.method == "POST" })
        val stored = widgetSnapshots(context).read()
        assertEquals(WidgetOutcome.Unreachable, stored.outcome)
        assertNull(stored.sending)
        assertNull(rendered.last().sending)
        assertEquals("the legacy tap projects no selection", R.drawable.cell_unselected, renderedCellDrawableResId(context, rendered.last(), legacyCell))
        val status = renderedStatusText(context, rendered.last())
        assertFalse("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
    }

    private data class FinalFailureCase(val name: String, val failure: IOException)

    @Test
    fun aFinalTransportFailureOfATapIsNotRetriedAndShowsUnreachable() {
        val cases = listOf(
            FinalFailureCase("a malformed URL", MalformedURLException("bad")),
            FinalFailureCase("an unknown service", UnknownServiceException("bad")),
            FinalFailureCase("a protocol failure", ProtocolException("bad")),
        )
        cases.forEach { case ->
            resetWidgetState()
            rendered.clear()
            transport = EchoSelectionTransport { throw case.failure }
            val result = runWorker(acceptedTapInput())
            assertTrue("case=${case.name} result=$result", result is ListenableWorker.Result.Success)
            assertEquals("case=${case.name}", 1, transport.requests.count { it.method == "POST" })
            val stored = widgetSnapshots(context).read()
            assertEquals("case=${case.name}", WidgetOutcome.Unreachable, stored.outcome)
            assertNull("case=${case.name}", stored.sending)
            assertEquals("case=${case.name}", stored, rendered.last())
            assertNull("case=${case.name}", rendered.last().sending)
            val status = renderedStatusText(context, rendered.last())
            assertFalse("case=${case.name} status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
        }
    }

    @Test
    fun aRetriedTapOlderThanTheTwentyFourHourDisplayBoundShowsNoRingAndNoWaitingStatusAfterTheRetry() {
        resetWidgetState()
        val clock = AtomicReference(TEST_TAPPED_AT)
        val accepting = WidgetController(context, { EchoSelectionTransport() }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { clock.get() })
        val accepted = accepting.acceptQueued(meeting)
        clock.set(accepted.accepted.tappedAt.plusSeconds(25 * 60 * 60L))
        rendered.clear()
        transport = EchoSelectionTransport { throw UnknownHostException("unreachable.example") }
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot }, clock = { clock.get() })
        }
        val result = runWorker(storedTapInput(context, accepted.workRequest))
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        val stored = widgetSnapshots(context).read()
        val last = rendered.last()
        val status = renderedStatusText(context, last)
        assertNull("the stored snapshot shows the ring of a tap older than 24 hours", stored.sending)
        assertNull("the last render shows the ring of a tap older than 24 hours", last.sending)
        assertFalse("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
        assertEquals("the tap older than 24 hours projects no selection", R.drawable.cell_unselected, renderedCellDrawableResId(context, last, meeting))
    }

    @Test
    fun anUpdateCheckTriggerFailureNeverChangesTheTapOrTheResult() {
        WidgetSelectionWorker.updateCheckTrigger = { _, _ -> throw IllegalStateException("enqueue failed") }
        val result = runWorker(acceptedTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals("the selection was posted once", 1, transport.requests.count { it.method == "POST" })
        assertEquals(Selection(meeting, since), widgetSnapshots(context).read().selection)
        val last = rendered.last()
        assertNull("the last rendered screen still shows a sending cell", last.sending)
        assertEquals("the saved selection is projected on the tapped cell", R.drawable.cell_selected, renderedCellDrawableResId(context, last, meeting))
    }

    @Test
    fun theUpdateCheckTriggerRunsAfterAFinishedSelection() {
        data class Case(
            val name: String,
            val transport: HttpTransport,
            val expectedResult: Class<out ListenableWorker.Result>,
            val expectedInvocations: Int,
        )
        val cases = listOf(
            Case("a finished selection", EchoSelectionTransport(), ListenableWorker.Result.Success::class.java, 1),
            Case(
                "a selection that is retried by a scheduled drain",
                FailingTapTransport(IOException("unexpected end of stream"), false),
                ListenableWorker.Result.Success::class.java,
                0,
            ),
        )
        cases.forEach { case ->
            resetWidgetState()
            rendered.clear()
            val invocations = mutableListOf<Instant>()
            WidgetSelectionWorker.updateCheckTrigger = { _, now -> invocations += now }
            WidgetSelectionWorker.controllerFactory = {
                WidgetController(it, { case.transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot })
            }
            val result = runWorker(acceptedTapInput())
            assertTrue("case=${case.name} result=$result", case.expectedResult.isInstance(result))
            assertEquals("case=${case.name}", case.expectedInvocations, invocations.size)
        }
    }

    @Test
    fun theUpdateCheckTriggerDoesNotRunAfterAGenericSelectionFailure() {
        transport = EchoSelectionTransport { throw IllegalStateException("boom") }
        val invocations = mutableListOf<Instant>()
        WidgetSelectionWorker.updateCheckTrigger = { _, now -> invocations += now }
        val result = runWorker(acceptedTapInput())
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertNull("the stored snapshot still holds the tap's ring", widgetSnapshots(context).read().sending)
        assertNull("the last rendered screen still shows the tap's ring", rendered.last().sending)
        assertEquals("the update-check trigger ran after a generic failure", 0, invocations.size)
    }

    private fun resetWidgetState() {
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
    }

    private fun awaitFinished(outcome: ListenableFuture<ListenableWorker.Result>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!outcome.isDone) {
            check(System.currentTimeMillis() < deadline) { "the stopped work did not finish" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aPendingEntryWhoseWorkWasCancelledIsDroppedAtTheNextWorkerRun() {
        val controller = WidgetController(context, { transport }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { acceptedAt })
        val accepted = controller.acceptQueued(meeting)
        WidgetWorkScheduler.enqueueWorkRequest(context, accepted.workRequest)
        WorkManager.getInstance(context).cancelWorkById(accepted.workRequest.id).result.get(30, TimeUnit.SECONDS)
        runWorker(Data.EMPTY)
        val probe = controller.acceptTap(meeting, workEnqueued = false)
        assertEquals(SelectionRequest.Select(meeting), probe.request)
    }

    @Test
    fun aPendingEntryWhoseWorkIsStillEnqueuedCountsForProjectionAfterEightDays() {
        val clock = AtomicReference(acceptedAt)
        val controller = WidgetController(context, { transport }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { clock.get() })
        val accepted = controller.acceptQueued(meeting)
        WidgetWorkScheduler.enqueueWorkRequest(context, accepted.workRequest)
        clock.set(acceptedAt.plus(Duration.ofDays(8)))
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot }, clock = { clock.get() }) }
        runWorker(Data.EMPTY)
        val state = checkNotNull(WorkManager.getInstance(context).getWorkInfoById(accepted.workRequest.id).get(30, TimeUnit.SECONDS)).state
        assertEquals(WorkInfo.State.ENQUEUED, state)
        val probe = controller.acceptTap(meeting, workEnqueued = false)
        assertEquals(SelectionRequest.Clear, probe.request)
    }

    @Test
    fun aPendingEntryWhoseWorkIsAbsentIsKeptInsideTheTenMinuteGracePeriod() {
        val clock = AtomicReference(acceptedAt)
        val controller = WidgetController(context, { transport }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { clock.get() })
        controller.acceptQueued(meeting)
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot }, clock = { clock.get() }) }
        runWorker(Data.EMPTY)
        val probe = controller.acceptTap(meeting, workEnqueued = false)
        assertEquals(SelectionRequest.Clear, probe.request)
    }

    @Test
    fun aPendingEntryWhoseWorkIsAbsentIsDroppedAfterTheTenMinuteGracePeriod() {
        val clock = AtomicReference(acceptedAt)
        val controller = WidgetController(context, { transport }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { clock.get() })
        controller.acceptQueued(meeting)
        clock.set(acceptedAt.plusSeconds(11 * 60))
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot }, clock = { clock.get() }) }
        runWorker(Data.EMPTY)
        val probe = controller.acceptTap(meeting, workEnqueued = false)
        assertEquals(SelectionRequest.Select(meeting), probe.request)
    }

    @Test
    fun anAcceptedQueuedTapStoresTheIdOfTheWorkRequestItBuilt() {
        val accepted = WidgetController(context, { transport }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { acceptedAt })
            .acceptQueued(meeting)
        assertEquals(listOf(accepted.workRequest.id), KeyValueQueuedTapRepository(widgetPreferences(context)).readAll().map { it.workId })
    }

    @Test
    fun anEnqueueFailureDiscardsTheAcceptedTapSoTheSameCellProjectsSelectAgain() {
        val controller = WidgetController(context, { transport }, { EchoSelectionTransport() }, publish = { _, _ -> }, clock = { acceptedAt })
        val accepted = controller.acceptQueued(meeting)
        controller.discardTap(meeting, accepted.accepted.tappedAt)
        val probe = controller.acceptTap(meeting, workEnqueued = false)
        assertEquals(SelectionRequest.Select(meeting), probe.request)
    }
}

private const val SELECT_MEETING_BODY = """{"project":"project-a","activity":"meeting","tappedAt":"2026-11-09T02:19:03.112Z"}"""

private const val EMPTY_SELECTION_BODY = """{"result":"ok","selection":null}"""

private class FailingTapTransport(private val failure: IOException, private val failsOnRedirectFollowUp: Boolean) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        if (request.method == "POST" && failsOnRedirectFollowUp) {
            return HttpResponse(302, "https://example.com/echo", "")
        }
        throw failure
    }
}

private class FirstPostFailsTransport(private val failure: IOException) : HttpTransport {
    val postBodies: MutableList<String?> = CopyOnWriteArrayList()

    override fun send(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return HttpResponse(200, null, EMPTY_SELECTION_BODY)
        postBodies += request.body
        if (postBodies.size == 1) throw failure
        return HttpResponse(200, null, EMPTY_SELECTION_BODY)
    }
}
