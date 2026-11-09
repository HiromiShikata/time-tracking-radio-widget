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
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueQueuedTapRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.QueuedTap
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.domain.resolveTapRequest
import com.hiromishikata.timetrackingradio.usecase.DiagnosticUploadUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSelectionUpdateUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSnapshotReadUseCase
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

private val SENDING_STATE_MAX_AGE: Duration = Duration.ofMinutes(10)

private val PENDING_TAP_MAX_AGE: Duration = Duration.ofMinutes(60)

class WidgetController(
    private val context: Context,
    private val transportFactory: () -> HttpTransport = { HttpUrlConnectionTransport() },
    private val uploadTransportFactory: () -> HttpTransport = {
        HttpUrlConnectionTransport(DIAGNOSTICS_UPLOAD_TIMEOUT_MILLIS)
    },
    private val publish: (WidgetSettings?, WidgetSnapshot) -> Unit = { settings, snapshot ->
        publishToAllWidgets(context, settings, snapshot)
    },
    private val clock: () -> Instant = { Instant.now() },
) {
    private val store = SharedPreferencesKeyValueStore(
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE),
    )
    private val settings = KeyValueWidgetSettingsRepository(store)
    private val snapshots = KeyValueWidgetSnapshotRepository(store)
    private val lockedSnapshots = LockedWidgetSnapshotRepository(snapshots)
    private val queuedTaps = KeyValueQueuedTapRepository(store)
    private val diagnosticLog = KeyValueDiagnosticLogRepository(store)

    fun render() {
        publish(settings.read(), settle(removing = null))
    }

    fun acceptTap(cell: CellKey, workEnqueued: Boolean): Instant {
        val tappedAt = clock()
        val loaded = settings.read()
        recorderFor(loaded).record(
            DiagnosticEventType.TAP_RECEIVED,
            "project=${cell.project}, activity=${cell.activity.code}",
        )
        if (loaded != null) {
            synchronized(WidgetStateLock) {
                queuedTaps.write(liveQueue(tappedAt) + QueuedTap(cell, tappedAt, workEnqueued))
            }
            render()
        }
        return tappedAt
    }

    suspend fun performTap(cell: CellKey, tappedAt: Instant) {
        WidgetScope.mutex.withLock {
            val loaded = settings.read()
            if (loaded != null) {
                var completed = false
                var awaitingNetwork = false
                try {
                    WidgetSelectionUpdateUseCase(
                        selections = selectionsFor(loaded),
                        snapshots = lockedSnapshots,
                        afterSendingStarted = { sendingStarted(cell) },
                    ) { clock() }.execute(cell, tappedAt)
                    completed = true
                } catch (unavailable: NetworkUnavailableException) {
                    awaitingNetwork = true
                    throw unavailable
                } finally {
                    publishSettled(removing = if (completed) cell else null) { entries ->
                        if (awaitingNetwork) entries.withWorkEnqueued(cell, true) else entries
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        WidgetScope.mutex.withLock {
            val loaded = settings.read()
            recorderFor(loaded).record(DiagnosticEventType.WIDGET_UPDATE_REQUESTED, "refresh")
            if (loaded != null) {
                try {
                    WidgetSnapshotReadUseCase(selectionsFor(loaded), lockedSnapshots) { clock() }.execute()
                } finally {
                    publishSettled(removing = null)
                }
            }
        }
    }

    fun clearSending(cell: CellKey?) {
        publishSettled(removing = cell)
    }

    fun uploadDiagnostics() {
        val loaded = settings.read() ?: return
        DiagnosticUploadUseCase(
            diagnosticLog,
            HttpDiagnosticsRepository(loaded.endpointUrl, uploadTransportFactory()),
            recorderFor(loaded),
        ).execute()
    }

    private fun sendingStarted(cell: CellKey) {
        val settled = settle(removing = null) { entries -> entries.withWorkEnqueued(cell, false) }
        publish(settings.read(), settled.copy(sending = settled.sending ?: resolveTapRequest(settled.selection, cell)))
    }

    private fun publishSettled(removing: CellKey?, update: (List<QueuedTap>) -> List<QueuedTap> = { it }) {
        publish(settings.read(), settle(removing, update))
    }

    private fun settle(
        removing: CellKey?,
        update: (List<QueuedTap>) -> List<QueuedTap> = { it },
    ): WidgetSnapshot = synchronized(WidgetStateLock) {
        val now = clock()
        val live = update(liveQueue(now))
        val remaining = if (removing == null) live else live.withoutFirst(removing)
        queuedTaps.write(remaining)
        val current = snapshots.read()
        val outcome = when {
            remaining.any { it.workEnqueued } -> WidgetOutcome.WaitingForNetwork
            current.outcome == WidgetOutcome.WaitingForNetwork -> WidgetOutcome.Settled
            else -> current.outcome
        }
        val settled = current.copy(outcome = outcome, sending = sendingRing(remaining, current.selection))
        snapshots.write(settled)
        settled
    }

    private fun liveQueue(now: Instant): List<QueuedTap> = queuedTaps.readAll().filterNot { entry ->
        val maxAge = if (entry.workEnqueued) PENDING_TAP_MAX_AGE else SENDING_STATE_MAX_AGE
        entry.acceptedAt.isBefore(now.minus(maxAge))
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
}

private fun sendingRing(queue: List<QueuedTap>, selection: Selection?): SelectionRequest? =
    queue.lastOrNull()?.let { resolveTapRequest(selection, it.cell) }

private fun List<QueuedTap>.withoutFirst(cell: CellKey): List<QueuedTap> {
    val index = indexOfFirst { it.cell == cell }
    return if (index < 0) this else filterIndexed { position, _ -> position != index }
}

private fun List<QueuedTap>.withWorkEnqueued(cell: CellKey, enqueued: Boolean): List<QueuedTap> {
    val index = indexOfFirst { it.cell == cell }
    return if (index < 0) this else mapIndexed { position, entry ->
        if (position == index) entry.copy(workEnqueued = enqueued) else entry
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
