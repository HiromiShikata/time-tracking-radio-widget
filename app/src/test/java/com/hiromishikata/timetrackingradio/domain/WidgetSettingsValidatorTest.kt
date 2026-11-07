package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSettingsValidatorTest {
    private val endpoint = "https://script.example.com/macros/s/key-value/exec/time-tracking-selection?key=k"

    @Test
    fun validatesEndpointAndProjectList() {
        val cases = listOf(
            Triple(endpoint, "project-a\nproject-b", WidgetSettingsValidation.Valid(WidgetSettings(endpoint, listOf("project-a", "project-b")))),
            Triple("  $endpoint  ", "  project-a \n\n project-b\n", WidgetSettingsValidation.Valid(WidgetSettings(endpoint, listOf("project-a", "project-b")))),
            Triple("", "project-a", WidgetSettingsValidation.Invalid(listOf(WidgetSettingsProblem.ENDPOINT_EMPTY))),
            Triple("http://script.example.com/exec", "project-a", WidgetSettingsValidation.Invalid(listOf(WidgetSettingsProblem.ENDPOINT_NOT_HTTPS))),
            Triple(endpoint, "  \n ", WidgetSettingsValidation.Invalid(listOf(WidgetSettingsProblem.PROJECTS_EMPTY))),
            Triple(endpoint, "project-a\nproject-a", WidgetSettingsValidation.Invalid(listOf(WidgetSettingsProblem.PROJECT_NAME_DUPLICATED))),
            Triple("", "", WidgetSettingsValidation.Invalid(listOf(WidgetSettingsProblem.ENDPOINT_EMPTY, WidgetSettingsProblem.PROJECTS_EMPTY))),
        )
        cases.forEach { (url, projects, expected) ->
            assertEquals("url=$url projects=$projects", expected, validateWidgetSettings(url, projects))
        }
    }
}
