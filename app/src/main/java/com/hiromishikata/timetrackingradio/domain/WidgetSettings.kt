package com.hiromishikata.timetrackingradio.domain

data class WidgetSettings(val endpointUrl: String, val projects: List<String>)

enum class WidgetSettingsProblem {
    ENDPOINT_EMPTY,
    ENDPOINT_NOT_HTTPS,
    PROJECTS_EMPTY,
    PROJECT_NAME_DUPLICATED,
}

sealed interface WidgetSettingsValidation {
    data class Valid(val settings: WidgetSettings) : WidgetSettingsValidation

    data class Invalid(val problems: List<WidgetSettingsProblem>) : WidgetSettingsValidation
}

fun parseProjectList(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

fun validateWidgetSettings(endpointUrl: String, projectsText: String): WidgetSettingsValidation {
    val endpoint = endpointUrl.trim()
    val projects = parseProjectList(projectsText)
    val problems = buildList {
        if (endpoint.isEmpty()) {
            add(WidgetSettingsProblem.ENDPOINT_EMPTY)
        } else if (!endpoint.startsWith("https://")) {
            add(WidgetSettingsProblem.ENDPOINT_NOT_HTTPS)
        }
        if (projects.isEmpty()) add(WidgetSettingsProblem.PROJECTS_EMPTY)
        if (projects.size != projects.distinct().size) add(WidgetSettingsProblem.PROJECT_NAME_DUPLICATED)
    }
    return if (problems.isEmpty()) {
        WidgetSettingsValidation.Valid(WidgetSettings(endpoint, projects))
    } else {
        WidgetSettingsValidation.Invalid(problems)
    }
}
