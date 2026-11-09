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

private val PENDING_TAP_MAX_AGE: Duration = Duration.ofHours(24)

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
        publishSettled()
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
                val request = resolveTapRequest(snapshots.read().selection, cell)
                queuedTaps.write(liveQueue(tappedAt) + QueuedTap(cell, tappedAt, request, workEnqueued, waiting = workEnqueued))
            }
            render()
        }
        return tappedAt
    }

    suspend fun performTap(cell: CellKey, tappedAt: Instant) {
        WidgetScope.mutex.withLock {
            val loaded = settings.read()
            if (loaded != null) {
                val request = acceptedRequest(cell, tappedAt)
                var completed = false
                var awaitingNetwork = false
                try {
                    WidgetSelectionUpdateUseCase(
                        selections = selectionsFor(loaded),
                        snapshots = lockedSnapshots,
                        afterSendingStarted = { sendingStarted(cell, tappedAt, request) },
                    ) { clock() }.execute(request, tappedAt)
                    completed = true
                } catch (unavailable: NetworkUnavailableException) {
                    awaitingNetwork = true
                    throw unavailable
                } finally {
                    publishSettled { entries ->
                        when {
                            awaitingNetwork -> entries.withPendingWork(cell, tappedAt, request)
                            completed -> entries.withoutTap(cell, tappedAt)
                            else -> entries
                        }
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
                    publishSettled()
                }
            }
        }
    }

    fun clearSending(cell: CellKey?) {
        publishSettled { entries -> cell?.let { entries.withoutFirst(it) } ?: entries }
    }

    fun discardTap(cell: CellKey, tappedAt: Instant) {
        publishSettled { entries -> entries.withoutTap(cell, tappedAt) }
    }

    fun markWaiting(cell: CellKey, tappedAt: Instant) {
        publishSettled { entries -> entries.withWaiting(cell, tappedAt, waiting = true) }
    }

    fun uploadDiagnostics() {
        val loaded = settings.read() ?: return
        DiagnosticUploadUseCase(
            diagnosticLog,
            HttpDiagnosticsRepository(loaded.endpointUrl, uploadTransportFactory()),
            recorderFor(loaded),
        ).execute()
    }

    private fun acceptedRequest(cell: CellKey, tappedAt: Instant): SelectionRequest = synchronized(WidgetStateLock) {
        val queue = liveQueue(clock())
        queue.getOrNull(queue.indexOfTap(cell, tappedAt))?.request
    } ?: resolveTapRequest(snapshots.read().selection, cell)

    private fun sendingStarted(cell: CellKey, tappedAt: Instant, request: SelectionRequest) {
        val settled = settle { entries -> entries.withWaiting(cell, tappedAt, waiting = false) }
        publish(settings.read(), settled.copy(sending = settled.sending ?: request))
    }

    private fun publishSettled(update: (List<QueuedTap>) -> List<QueuedTap> = { it }) {
        publish(settings.read(), settle(update))
    }

    private fun settle(update: (List<QueuedTap>) -> List<QueuedTap> = { it }): WidgetSnapshot = synchronized(WidgetStateLock) {
        val now = clock()
        val remaining = update(liveQueue(now))
        queuedTaps.write(remaining)
        val ring = remaining.ringEntry()
        val current = snapshots.read()
        val outcome = when {
            ring?.waiting == true -> WidgetOutcome.WaitingForNetwork
            current.outcome == WidgetOutcome.WaitingForNetwork -> WidgetOutcome.Settled
            else -> current.outcome
        }
        val settled = current.copy(outcome = outcome, sending = ring?.request)
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

private fun List<QueuedTap>.ringEntry(): QueuedTap? = firstOrNull { it.workEnqueued } ?: lastOrNull()

private fun List<QueuedTap>.indexOfTap(cell: CellKey, tappedAt: Instant): Int {
    val exact = indexOfFirst { it.cell == cell && it.acceptedAt == tappedAt }
    return if (exact >= 0) exact else indexOfFirst { it.cell == cell }
}

private fun List<QueuedTap>.withoutFirst(cell: CellKey): List<QueuedTap> {
    val index = indexOfFirst { it.cell == cell }
    return if (index < 0) this else filterIndexed { position, _ -> position != index }
}

private fun List<QueuedTap>.withoutTap(cell: CellKey, tappedAt: Instant): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) this else filterIndexed { position, _ -> position != index }
}

private fun List<QueuedTap>.withWaiting(cell: CellKey, tappedAt: Instant, waiting: Boolean): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) this else mapIndexed { position, entry ->
        if (position == index) entry.copy(waiting = waiting) else entry
    }
}

private fun List<QueuedTap>.withPendingWork(cell: CellKey, tappedAt: Instant, request: SelectionRequest): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) {
        this + QueuedTap(cell, tappedAt, request, workEnqueued = true, waiting = true)
    } else {
        mapIndexed { position, entry ->
            if (position == index) entry.copy(workEnqueued = true, waiting = true) else entry
        }
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
