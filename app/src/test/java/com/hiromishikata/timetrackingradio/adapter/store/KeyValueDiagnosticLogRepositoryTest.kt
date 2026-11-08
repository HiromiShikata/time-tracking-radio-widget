package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class KeyValueDiagnosticLogRepositoryTest {
    private fun at(seconds: Long): Instant = Instant.EPOCH.plusSeconds(seconds)

    @Test
    fun keepsOnlyTheNewestEntriesInNewestFirstOrder() {
        val repository = KeyValueDiagnosticLogRepository(InMemoryKeyValueStore())
        (1..205).forEach { repository.append(at(it.toLong()), DiagnosticEventType.TAP_RECEIVED, "entry-$it") }
        val entries = repository.readAll()
        assertEquals(200, entries.size)
        assertEquals("entry-205", entries.first().detail)
        assertEquals("entry-6", entries.last().detail)
    }

    @Test
    fun entriesSurviveAFreshRepositoryOverTheSameStore() {
        val store = InMemoryKeyValueStore()
        KeyValueDiagnosticLogRepository(store).append(at(7), DiagnosticEventType.HTTP_RESPONSE, "status=200")
        val entries = KeyValueDiagnosticLogRepository(store).readAll()
        assertEquals(1, entries.size)
        assertEquals(at(7), entries.single().at)
        assertEquals(DiagnosticEventType.HTTP_RESPONSE, entries.single().type)
        assertEquals("status=200", entries.single().detail)
        assertEquals(false, entries.single().delivered)
    }

    @Test
    fun undeliveredEntriesAreReadOldestFirstAndStayPendingUntilMarked() {
        val repository = KeyValueDiagnosticLogRepository(InMemoryKeyValueStore())
        (1..3).forEach { repository.append(at(it.toLong()), DiagnosticEventType.TAP_RECEIVED, "entry-$it") }
        val firstBatch = repository.readUndelivered(2)
        assertEquals(listOf("entry-1", "entry-2"), firstBatch.map { it.detail })
        repository.markDelivered(firstBatch.map { it.id }.toSet())
        assertEquals(listOf("entry-3"), repository.readUndelivered(200).map { it.detail })
        assertEquals(listOf(true, true, false), repository.readAll().reversed().map { it.delivered })
    }
}
