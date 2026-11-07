package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyValueWidgetSettingsRepositoryTest {
    @Test
    fun readsNullUntilWrittenThenRoundTrips() {
        val store = InMemoryKeyValueStore()
        val repository = KeyValueWidgetSettingsRepository(store)
        assertNull(repository.read())
        val settings = WidgetSettings("https://script.example.com/exec?key=k", listOf("project-a", "project-b"))
        repository.write(settings)
        assertEquals(settings, repository.read())
    }
}
