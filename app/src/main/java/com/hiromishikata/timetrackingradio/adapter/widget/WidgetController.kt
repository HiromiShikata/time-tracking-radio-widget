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
import java.util.UUID

private val SENDING_STATE_MAX_AGE: Duration = Duration.ofMinutes(10)

private val PENDING_TAP_DISPLAY_MAX_AGE: Duration = Duration.ofHours(24)

data class AcceptedTap(val tappedAt: Instant, val request: SelectionRequest)

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

    fun acceptTap(cell: CellKey, workEnqueued: Boolean): AcceptedTap {
        val tappedAt = clock()
        val loaded = settings.read()
        recorderFor(loaded).record(
            DiagnosticEventType.TAP_RECEIVED,
            "project=${cell.project}, activity=${cell.activity.code}",
        )
        val request = synchronized(WidgetStateLock) {
            val pending = storedQueue(tappedAt)
            val resolved = resolveTapRequest(projectSelection(snapshots.read().selection, pending), cell)
            if (loaded != null) {
                queuedTaps.write(pending + QueuedTap(cell, tappedAt, resolved, workEnqueued, waiting = workEnqueued, workId = null))
            }
            resolved
        }
        if (loaded != null) {
            render()
        }
        return AcceptedTap(tappedAt, request)
    }

    suspend fun performTap(cell: CellKey, request: SelectionRequest, tappedAt: Instant) {
        WidgetScope.mutex.withLock { sendTap(cell, request, tappedAt) }
    }

    suspend fun performLegacyTap(cell: CellKey) {
        WidgetScope.mutex.withLock {
            val loaded = settings.read() ?: return
            val selections = selectionsFor(loaded)
            try {
                WidgetSelectionUpdateUseCase(
                    selections = selections,
                    snapshots = lockedSnapshots,
                    afterSendingStarted = { publishInFlight() },
                ) { clock() }.executeUnstamped(cell, selections)
            } finally {
                publishInFlight()
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

    fun discardTap(cell: CellKey, tappedAt: Instant) {
        publishSettled { entries -> entries.withoutTap(cell, tappedAt) }
    }

    fun markWaiting(cell: CellKey, tappedAt: Instant) {
        publishSettled { entries -> entries.withWaiting(cell, tappedAt, waiting = true) }
    }

    fun attachWorkId(cell: CellKey, tappedAt: Instant, workId: UUID) {
        publishSettled { entries -> entries.withWorkId(cell, tappedAt, workId) }
    }

    fun dropFinishedWork(isFinished: (UUID) -> Boolean) {
        val workIds = synchronized(WidgetStateLock) { storedQueue(clock()).mapNotNull { it.workId } }
        val finished = workIds.filter(isFinished).toSet()
        if (finished.isNotEmpty()) {
            publishSettled { entries -> entries.filterNot { it.workId in finished } }
        }
    }

    fun uploadDiagnostics() {
        val loaded = settings.read() ?: return
        DiagnosticUploadUseCase(
            diagnosticLog,
            HttpDiagnosticsRepository(loaded.endpointUrl, uploadTransportFactory()),
            recorderFor(loaded),
        ).execute()
    }

    private fun sendTap(cell: CellKey, request: SelectionRequest, tappedAt: Instant) {
        val loaded = settings.read()
        if (loaded == null) {
            discardTap(cell, tappedAt)
            return
        }
        var awaitingNetwork = false
        try {
            WidgetSelectionUpdateUseCase(
                selections = selectionsFor(loaded),
                snapshots = lockedSnapshots,
                afterSendingStarted = { sendingStarted(cell, tappedAt) },
            ) { clock() }.execute(request, tappedAt)
        } catch (unavailable: NetworkUnavailableException) {
            awaitingNetwork = true
            throw unavailable
        } finally {
            val now = clock()
            publishSettled { entries ->
                if (awaitingNetwork) {
                    entries.withPendingWork(cell, tappedAt, request, now)
                } else {
                    entries.withoutTap(cell, tappedAt)
                }
            }
        }
    }

    private fun sendingStarted(cell: CellKey, tappedAt: Instant) {
        publish(settings.read(), settle { entries -> entries.withWaiting(cell, tappedAt, waiting = false) })
    }

    private fun publishInFlight() {
        val current = snapshots.read()
        publish(settings.read(), current.copy(statusRequest = current.sending))
    }

    private fun publishSettled(update: (List<QueuedTap>) -> List<QueuedTap> = { it }) {
        publish(settings.read(), settle(update))
    }

    private fun settle(update: (List<QueuedTap>) -> List<QueuedTap> = { it }): WidgetSnapshot = synchronized(WidgetStateLock) {
        val now = clock()
        val remaining = update(storedQueue(now))
        queuedTaps.write(remaining)
        val shown = remaining.displayed(now)
        val head = shown.firstOrNull()
        val last = shown.lastOrNull()
        val current = snapshots.read()
        val outcome = when {
            head?.waiting == true -> WidgetOutcome.WaitingForNetwork
            current.outcome == WidgetOutcome.WaitingForNetwork -> WidgetOutcome.Settled
            else -> current.outcome
        }
        val settled = current.copy(outcome = outcome, sending = last?.request, statusRequest = head?.request)
        snapshots.write(settled)
        settled
    }

    private fun storedQueue(now: Instant): List<QueuedTap> = queuedTaps.readAll().filterNot { entry ->
        !entry.workEnqueued && entry.acceptedAt.isBefore(now.minus(SENDING_STATE_MAX_AGE))
    }

    private fun recorderFor(current: WidgetSettings?): DiagnosticRecorder = DiagnosticRecorder(
        diagnosticLog,
        endpointKeySecrets(current?.endpointUrl.orEmpty()),
    ) { Instant.now() }

    private fun selectionsFor(current: WidgetSettings): HttpSelectionRepository {
        val recorder = recorderFor(current)
        return HttpSelectionRepository(
            current.endpointUrl,
            DiagnosticHttpTransport(transportFactory(), current.endpointUrl, recorder),
            recorder,
        )
    }
}

private fun List<QueuedTap>.indexOfTap(cell: CellKey, tappedAt: Instant): Int =
    indexOfFirst { it.cell == cell && it.acceptedAt == tappedAt }

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

private fun List<QueuedTap>.withWorkId(cell: CellKey, tappedAt: Instant, workId: UUID): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) this else mapIndexed { position, entry ->
        if (position == index) entry.copy(workId = workId) else entry
    }
}

private fun List<QueuedTap>.displayed(now: Instant): List<QueuedTap> = filterNot { entry ->
    entry.workEnqueued && entry.acceptedAt.isBefore(now.minus(PENDING_TAP_DISPLAY_MAX_AGE))
}

private fun projectSelection(confirmed: Selection?, pending: List<QueuedTap>): Selection? =
    pending.filter { it.workEnqueued }.fold(confirmed) { _, entry ->
        when (val request = entry.request) {
            is SelectionRequest.Select -> Selection(request.cell, entry.acceptedAt)
            SelectionRequest.Clear -> null
        }
    }

private fun List<QueuedTap>.withPendingWork(
    cell: CellKey,
    tappedAt: Instant,
    request: SelectionRequest,
    now: Instant,
): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) {
        if (tappedAt.isBefore(now.minus(PENDING_TAP_DISPLAY_MAX_AGE))) {
            this
        } else {
            this + QueuedTap(cell, tappedAt, request, workEnqueued = true, waiting = true, workId = null)
        }
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
