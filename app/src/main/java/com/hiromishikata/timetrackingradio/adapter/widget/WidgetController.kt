package com.hiromishikata.timetrackingradio.adapter.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.hiromishikata.timetrackingradio.adapter.http.DIAGNOSTICS_UPLOAD_TIMEOUT_MILLIS
import com.hiromishikata.timetrackingradio.adapter.http.DiagnosticHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpDiagnosticsRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpSelectionRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.usecase.DiagnosticUploadUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSelectionUpdateUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSnapshotReadUseCase
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class WidgetController(
    private val context: Context,
    private val transportFactory: () -> HttpTransport = { HttpUrlConnectionTransport() },
    private val uploadTransportFactory: () -> HttpTransport = {
        HttpUrlConnectionTransport(DIAGNOSTICS_UPLOAD_TIMEOUT_MILLIS)
    },
    private val publish: (WidgetSettings?, WidgetSnapshot) -> Unit = { settings, snapshot ->
        publishToAllWidgets(context, settings, snapshot)
    },
) {
    private val store = SharedPreferencesKeyValueStore(
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE),
    )
    private val settings = KeyValueWidgetSettingsRepository(store)
    private val snapshots = KeyValueWidgetSnapshotRepository(store)
    private val diagnosticLog = KeyValueDiagnosticLogRepository(store)

    fun render() {
        publish(settings.read(), snapshots.read())
    }

    /**
     * The diagnostics upload runs after the mutex is released, so a slow or failing endpoint never delays
     * the next tap's sending render or the final render of this request.
     */
    suspend fun refresh() {
        var uploadTarget: WidgetSettings? = null
        try {
            WidgetScope.mutex.withLock {
                val loaded = settings.read()
                uploadTarget = loaded
                recorderFor(loaded).record(DiagnosticEventType.WIDGET_UPDATE_REQUESTED, "refresh")
                if (loaded != null) {
                    try {
                        WidgetSnapshotReadUseCase(selectionsFor(loaded), snapshots) { Instant.now() }.execute()
                    } finally {
                        render()
                    }
                }
            }
        } finally {
            uploadTarget?.let { uploadDiagnostics(it) }
        }
    }

    suspend fun tap(cell: CellKey) {
        var uploadTarget: WidgetSettings? = null
        try {
            WidgetScope.mutex.withLock {
                val loaded = settings.read()
                uploadTarget = loaded
                recorderFor(loaded).record(
                    DiagnosticEventType.TAP_RECEIVED,
                    "project=${cell.project}, activity=${cell.activity.code}",
                )
                if (loaded != null) {
                    try {
                        WidgetSelectionUpdateUseCase(
                            selections = selectionsFor(loaded),
                            snapshots = snapshots,
                            afterSendingStarted = { render() },
                        ) { Instant.now() }.execute(cell)
                    } finally {
                        render()
                    }
                }
            }
        } finally {
            uploadTarget?.let { uploadDiagnostics(it) }
        }
    }

    private fun recorderFor(current: WidgetSettings?): DiagnosticRecorder = DiagnosticRecorder(
        diagnosticLog,
        endpointKeySecrets(current?.endpointUrl.orEmpty()),
    ) { Instant.now() }

    private fun selectionsFor(current: WidgetSettings): SelectionRepository {
        val recorder = recorderFor(current)
        return HttpSelectionRepository(
            current.endpointUrl,
            DiagnosticHttpTransport(transportFactory(), current.endpointUrl, recorder),
            recorder,
        )
    }

    private fun uploadDiagnostics(current: WidgetSettings) {
        DiagnosticUploadUseCase(
            diagnosticLog,
            HttpDiagnosticsRepository(current.endpointUrl, uploadTransportFactory()),
            recorderFor(current),
        ).execute()
    }
}

private fun publishToAllWidgets(context: Context, settings: WidgetSettings?, snapshot: WidgetSnapshot) {
    val views = SelectionWidgetRemoteViewsFactory(context).build(
        settings = settings,
        snapshot = snapshot,
        now = Instant.now(),
    )
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, SelectionWidgetProvider::class.java))
    ids.forEach { manager.updateAppWidget(it, views) }
}
