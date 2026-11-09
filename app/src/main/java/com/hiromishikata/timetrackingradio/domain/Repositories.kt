package com.hiromishikata.timetrackingradio.domain

import java.time.Instant

interface SelectionRepository {
    fun read(): SelectionResponse

    fun update(request: SelectionRequest, tappedAt: Instant): SelectionResponse
}

interface WidgetSnapshotRepository {
    fun read(): WidgetSnapshot

    fun write(snapshot: WidgetSnapshot)
}

interface WidgetSettingsRepository {
    fun read(): WidgetSettings?

    fun write(settings: WidgetSettings)
}

interface ReleaseRepository {
    fun readLatest(): LatestRelease
}
