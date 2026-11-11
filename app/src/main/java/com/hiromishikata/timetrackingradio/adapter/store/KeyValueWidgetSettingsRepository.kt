package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSettingsRepository

class KeyValueWidgetSettingsRepository(private val store: KeyValueStore) : WidgetSettingsRepository {
    override fun read(): WidgetSettings? {
        val endpoint = store.read(ENDPOINT_KEY) ?: return null
        val projects = store.read(PROJECTS_KEY)?.split(LINE_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
        return WidgetSettings(endpoint, projects, showStatusDetails = readShowStatusDetails())
    }

    override fun write(settings: WidgetSettings) {
        store.write(ENDPOINT_KEY, settings.endpointUrl)
        store.write(PROJECTS_KEY, settings.projects.joinToString(LINE_SEPARATOR))
        writeShowStatusDetails(settings.showStatusDetails)
    }

    fun readShowStatusDetails(): Boolean = store.read(SHOW_STATUS_DETAILS_KEY) != FALSE_TEXT

    fun writeShowStatusDetails(show: Boolean) {
        store.write(SHOW_STATUS_DETAILS_KEY, show.toString())
    }

    private companion object {
        const val ENDPOINT_KEY = "endpoint_url"
        const val PROJECTS_KEY = "projects"
        const val SHOW_STATUS_DETAILS_KEY = "show_status_details"
        const val FALSE_TEXT = "false"
        const val LINE_SEPARATOR = "\n"
    }
}
