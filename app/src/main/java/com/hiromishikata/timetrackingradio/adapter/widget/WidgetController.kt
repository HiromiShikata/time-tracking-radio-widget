package com.hiromishikata.timetrackingradio.adapter.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.hiromishikata.timetrackingradio.adapter.http.HttpSelectionRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.usecase.WidgetSelectionUpdateUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSnapshotReadUseCase
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class WidgetController(
    private val context: Context,
    private val transportFactory: () -> HttpTransport = { HttpUrlConnectionTransport() },
) {
    private val store = SharedPreferencesKeyValueStore(
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE),
    )
    private val settings = KeyValueWidgetSettingsRepository(store)
    private val snapshots = KeyValueWidgetSnapshotRepository(store)

    fun render() {
        val views = SelectionWidgetRemoteViewsFactory(context).build(
            settings = settings.read(),
            snapshot = snapshots.read(),
            now = Instant.now(),
        )
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, SelectionWidgetProvider::class.java))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    suspend fun refresh() {
        WidgetScope.mutex.withLock {
            val current = settings.read() ?: return@withLock
            WidgetSnapshotReadUseCase(selectionsFor(current), snapshots) { Instant.now() }.execute()
            render()
        }
    }

    suspend fun tap(cell: CellKey) {
        WidgetScope.mutex.withLock {
            val current = settings.read() ?: return@withLock
            WidgetSelectionUpdateUseCase(
                selections = selectionsFor(current),
                snapshots = snapshots,
                afterSendingStarted = { render() },
            ) { Instant.now() }.execute(cell)
            render()
        }
    }

    private fun selectionsFor(current: WidgetSettings): SelectionRepository =
        HttpSelectionRepository(current.endpointUrl, transportFactory())
}
