package com.hiromishikata.timetrackingradio.adapter.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class KeyValueLastUpdateCheckRepositoryTest {
    private val lastUpdateCheckKey = "last_update_check_at"

    @Test
    fun aWrittenTimestampIsReadBack() {
        val cases = listOf(
            Instant.EPOCH,
            Instant.parse("2026-11-10T00:00:00Z"),
            Instant.parse("2026-11-09T02:19:03.123456789Z"),
        )
        cases.forEach { at ->
            val repository = KeyValueLastUpdateCheckRepository(InMemoryKeyValueStore())
            repository.write(at)
            assertEquals(at.toString(), at, repository.read())
        }
    }

    @Test
    fun anAbsentValueReadsAsNull() {
        assertNull(KeyValueLastUpdateCheckRepository(InMemoryKeyValueStore()).read())
    }

    @Test
    fun aMalformedValueReadsAsNullAndMakesTheOnDemandCheckDue() {
        val store = InMemoryKeyValueStore()
        store.write(lastUpdateCheckKey, "not-a-time")
        assertNull(KeyValueLastUpdateCheckRepository(store).read())
    }
}
