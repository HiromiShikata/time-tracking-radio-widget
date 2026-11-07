package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.WidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.WidgetSettingsValidation
import com.hiromishikata.timetrackingradio.domain.validateWidgetSettings

class WidgetSettingsUpdateUseCase(private val settings: WidgetSettingsRepository) {
    fun execute(endpointUrl: String, projectsText: String): WidgetSettingsValidation {
        val validation = validateWidgetSettings(endpointUrl, projectsText)
        if (validation is WidgetSettingsValidation.Valid) settings.write(validation.settings)
        return validation
    }
}
