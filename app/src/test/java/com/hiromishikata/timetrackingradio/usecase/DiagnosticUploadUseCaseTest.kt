package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadResult
import com.hiromishikata.timetrackingradio.domain.DIAGNOSTICS_UPLOAD_LIMIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DiagnosticUploadUseCaseTest {
    private val clock = { Instant.parse("2026-11-08T00:05:00Z") }

    private fun useCase(log: FakeDiagnosticLogRepository, uploads: FakeDiagnosticsUploadRepository) =
        DiagnosticUploadUseCase(log, uploads, DiagnosticRecorder(log, emptySet(), clock))

    @Test
    fun nothingPendingMeansNoRequest() {
        val log = FakeDiagnosticLogRepository()
        val uploads = FakeDiagnosticsUploadRepository()
        useCase(log, uploads).execute()
        assertEquals(emptyList<List<*>>(), uploads.calls)
        assertEquals(emptyList<String>(), log.recorded.map { it.detail })
    }

    @Test
    fun aDeliveredUploadMarksTheEntriesAndRecordsTheResult() {
        val log = FakeDiagnosticLogRepository()
        log.append(Instant.EPOCH, DiagnosticEventType.TAP_RECEIVED, "first")
        log.append(Instant.EPOCH, DiagnosticEventType.REQUEST_SENT, "second")
        val uploads = FakeDiagnosticsUploadRepository()
        useCase(log, uploads).execute()
        assertEquals(listOf(listOf("first", "second")), uploads.calls.map { batch -> batch.map { it.detail } })
        assertEquals(listOf(true, true, false), log.recorded.map { it.delivered })
        assertEquals("delivered 2 entries", log.recorded.last().detail)
        assertEquals(DiagnosticEventType.DIAGNOSTICS_UPLOAD, log.recorded.last().type)
    }

    @Test
    fun aFailedUploadKeepsTheEntriesAndRecordsTheFailureOnceWithoutRetrying() {
        val log = FakeDiagnosticLogRepository()
        log.append(Instant.EPOCH, DiagnosticEventType.TAP_RECEIVED, "first")
        val uploads = FakeDiagnosticsUploadRepository { DiagnosticsUploadResult.Failed("ng") }
        useCase(log, uploads).execute()
        assertEquals(1, uploads.calls.size)
        assertEquals(listOf(false, false), log.recorded.map { it.delivered })
        assertEquals("failed: ng", log.recorded.last().detail)
    }

    @Test
    fun anUploadThatThrowsIsRecordedAndNotRethrown() {
        val log = FakeDiagnosticLogRepository()
        log.append(Instant.EPOCH, DiagnosticEventType.TAP_RECEIVED, "first")
        val uploads = FakeDiagnosticsUploadRepository { throw IllegalStateException("boom") }
        useCase(log, uploads).execute()
        assertEquals("failed: IllegalStateException: boom", log.recorded.last().detail)
        assertFalse(log.recorded.first().delivered)
    }

    @Test
    fun aSecondUploadStartedWhileAnotherIsInFlightSendsNothing() {
        val log = FakeDiagnosticLogRepository()
        log.append(Instant.EPOCH, DiagnosticEventType.TAP_RECEIVED, "first")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val uploads = FakeDiagnosticsUploadRepository {
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            DiagnosticsUploadResult.Delivered
        }
        val inFlight = thread { useCase(log, uploads).execute() }
        assertTrue("the first upload started", started.await(5, TimeUnit.SECONDS))
        useCase(log, uploads).execute()
        release.countDown()
        inFlight.join(5_000)
        assertEquals(1, uploads.calls.size)
        assertTrue(log.recorded.first().delivered)
    }

    @Test
    fun oneRequestCarriesAtMostTheUploadLimit() {
        val log = FakeDiagnosticLogRepository()
        (1..DIAGNOSTICS_UPLOAD_LIMIT + 5).forEach { log.append(Instant.EPOCH, DiagnosticEventType.TAP_RECEIVED, "entry-$it") }
        val uploads = FakeDiagnosticsUploadRepository()
        useCase(log, uploads).execute()
        assertEquals(DIAGNOSTICS_UPLOAD_LIMIT, uploads.calls.single().size)
    }
}
