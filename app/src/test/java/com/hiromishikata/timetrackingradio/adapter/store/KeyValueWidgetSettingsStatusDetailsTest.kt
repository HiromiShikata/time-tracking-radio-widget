package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyValueWidgetSettingsStatusDetailsTest {
    private val endpoint = "https://script.example.com/exec?key=k"

    @Test
    fun theStatusDetailsChoiceRoundTripsThroughTheSettingsStore() {
        listOf(false, true).forEach { choice ->
            val store = InMemoryKeyValueStore()
            val repository = KeyValueWidgetSettingsRepository(store)
            repository.write(WidgetSettings(endpoint, listOf("project-a"), showStatusDetails = choice))
            assertEquals("choice=$choice", choice, repository.read()?.showStatusDetails)
        }
    }

    @Test
    fun settingsStoredBeforeTheChoiceExistedReadWithStatusDetailsShown() {
        val store = InMemoryKeyValueStore()
        store.write("endpoint_url", endpoint)
        store.write("projects", "project-a")
        val read = KeyValueWidgetSettingsRepository(store).read()
        assertTrue("read=$read", read?.showStatusDetails == true)
        assertEquals(WidgetSettings(endpoint, listOf("project-a")), read)
    }
}
