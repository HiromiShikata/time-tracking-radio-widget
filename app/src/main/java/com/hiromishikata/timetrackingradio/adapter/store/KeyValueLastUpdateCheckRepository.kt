package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.LastUpdateCheckRepository
import java.time.Instant
import java.time.format.DateTimeParseException

class KeyValueLastUpdateCheckRepository(private val store: KeyValueStore) : LastUpdateCheckRepository {
    override fun read(): Instant? {
        val text = store.read(LAST_CHECK_KEY) ?: return null
        return try {
            Instant.parse(text)
        } catch (unparseable: DateTimeParseException) {
            null
        }
    }

    override fun write(at: Instant) {
        store.write(LAST_CHECK_KEY, at.toString())
    }

    private companion object {
        const val LAST_CHECK_KEY = "last_update_check_at"
    }
}
