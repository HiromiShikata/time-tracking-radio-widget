package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSettingsRepository

class WidgetSettingsReadUseCase(private val settings: WidgetSettingsRepository) {
    fun execute(): WidgetSettings? = settings.read()
}
