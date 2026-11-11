package com.hiromishikata.timetrackingradio.adapter.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
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
import com.hiromishikata.timetrackingradio.domain.NetworkState
import com.hiromishikata.timetrackingradio.domain.RequestTimer
import com.hiromishikata.timetrackingradio.domain.RetryKind
import com.hiromishikata.timetrackingradio.domain.retryScheduledDetail
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.domain.tapReceivedDetail
import com.hiromishikata.timetrackingradio.domain.workStartedDetail
import com.hiromishikata.timetrackingradio.domain.resolveTapRequest
import com.hiromishikata.timetrackingradio.usecase.DiagnosticUploadUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSelectionUpdateUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSnapshotReadUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val SENDING_STATE_MAX_AGE: Duration = Duration.ofMinutes(10)

private val PENDING_TAP_DISPLAY_MAX_AGE: Duration = Duration.ofHours(24)

private val WORK_ABSENT_GRACE_PERIOD: Duration = Duration.ofMinutes(10)

private const val LAST_WIDGET_STATUS_KEY = "last_widget_status_text"

private val FINISHED_WORK_STATES = setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)

data class AcceptedTap(val tappedAt: Instant, val request: SelectionRequest)

data class DrainResult(val answered: Int, val unanswered: Int)

data class QueuedTapAccept(val accepted: AcceptedTap, val workRequest: OneTimeWorkRequest)

class WorkRun(val runAttemptCount: Int, val readNetwork: () -> NetworkState)

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
    private val describeStatus: (WidgetSnapshot, Instant) -> String = { snapshot, now ->
        SelectionWidgetRemoteViewsFactory(context).statusText(snapshot, now)
    },
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
        val loaded = settings.read()
        val (accepted, queuedTaps) = synchronized(WidgetStateLock) {
            val tappedAt = clock()
            val pending = storedQueue(tappedAt)
            val resolved = resolveTapRequest(projectSelection(snapshots.read().selection, pending.filter { it.workEnqueued }), cell)
            val next = QueuedTap(cell, tappedAt, resolved, workEnqueued, waiting = false, workId = null)
            if (loaded != null) {
                queuedTaps.write(pending + next)
            }
            AcceptedTap(tappedAt, resolved) to (pending + next).count { it.workEnqueued }
        }
        recordTap(loaded, cell, accepted.tappedAt, accepted.request, queuedTaps, workId = null)
        if (loaded != null) {
            render()
        }
        return accepted
    }

    fun acceptQueuedTap(
        cell: CellKey,
        buildWorkRequest: (tappedAt: Instant, request: SelectionRequest) -> OneTimeWorkRequest,
    ): QueuedTapAccept {
        val loaded = settings.read()
        val offline = !readDeviceNetworkState(context).activeNetwork
        val (queued, queuedTaps) = synchronized(WidgetStateLock) {
            val tappedAt = clock()
            val pending = storedQueue(tappedAt)
            val resolved = resolveTapRequest(projectSelection(snapshots.read().selection, pending.filter { it.workEnqueued }), cell)
            val workRequest = buildWorkRequest(tappedAt, resolved)
            val next = QueuedTap(cell, tappedAt, resolved, workEnqueued = true, waiting = offline, workId = workRequest.id)
            if (loaded != null) {
                queuedTaps.write(pending + next)
            }
            QueuedTapAccept(AcceptedTap(tappedAt, resolved), workRequest) to (pending + next).count { it.workEnqueued }
        }
        recordTap(loaded, cell, queued.accepted.tappedAt, queued.accepted.request, queuedTaps, queued.workRequest.id.toString())
        if (loaded != null) {
            render()
        }
        return queued
    }

    suspend fun performTap(cell: CellKey, request: SelectionRequest, tappedAt: Instant) {
        WidgetScope.mutex.withLock { sendTap(cell, request, tappedAt) }
    }

    suspend fun performWorkTap(cell: CellKey, request: SelectionRequest, tappedAt: Instant, run: WorkRun): DrainResult =
        WidgetScope.mutex.withLock {
            when {
                !isPending(cell, tappedAt) -> DrainResult(answered = 0, unanswered = 0)
                hasOlderPending(tappedAt) -> drainLocked(run)
                else -> {
                    recordWorkStarted(tappedAt, run)
                    sendTap(cell, request, tappedAt)
                    DrainResult(answered = 1, unanswered = 0)
                }
            }
        }

    suspend fun drainPendingTaps(run: WorkRun): DrainResult = WidgetScope.mutex.withLock { drainLocked(run) }

    private fun drainLocked(run: WorkRun): DrainResult {
        val pending = synchronized(WidgetStateLock) { storedQueue(clock()) }
            .filter { it.workEnqueued }
            .sortedBy { it.acceptedAt }
        var answered = 0
        var unanswered = 0
        for (entry in pending) {
            if (!isPending(entry.cell, entry.acceptedAt)) continue
            recordWorkStarted(entry.acceptedAt, run)
            try {
                sendTap(entry.cell, entry.request, entry.acceptedAt)
                answered += 1
            } catch (unavailable: NetworkUnavailableException) {
                unanswered += 1
                logWidgetWorkFailure(unavailable)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logWidgetWorkFailure(failure)
            }
        }
        return DrainResult(answered, unanswered)
    }

    fun releaseWork(workIds: Set<UUID>): Set<UUID> = synchronized(WidgetStateLock) {
        val entries = storedQueue(clock())
        val released = entries.mapNotNull { it.workId }.filter { it in workIds }.toSet()
        if (released.isNotEmpty()) {
            queuedTaps.write(entries.map { entry -> if (entry.workId?.let { it in released } == true) entry.copy(workId = null) else entry })
        }
        released
    }

    fun recordRetryScheduled(kind: RetryKind, attempt: Int, delaySeconds: Long) {
        recorderFor(settings.read()).record(DiagnosticEventType.RETRY_SCHEDULED, retryScheduledDetail(kind, attempt, delaySeconds))
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
        publishSettled { entries -> entries.withWaiting(cell, tappedAt) }
    }

    fun dropFinishedWork(ownCell: CellKey?, ownTappedAt: Instant?, fetchWorkInfos: () -> List<WorkInfo>) {
        val now = clock()
        val stored = synchronized(WidgetStateLock) { storedQueue(now) }
        if (stored.none { it.workId != null }) {
            return
        }
        val states = try {
            fetchWorkInfos().associate { it.id to it.state }
        } catch (failure: Exception) {
            logWidgetWorkFailure(failure)
            return
        }
        publishSettled { entries ->
            entries.filterNot { entry ->
                !entry.isOwnTap(ownCell, ownTappedAt) && entry.isReconciledAway(states, now)
            }
        }
    }

    fun uploadDiagnostics() {
        val loaded = settings.read() ?: return
        val recorder = recorderFor(loaded)
        DiagnosticUploadUseCase(
            diagnosticLog,
            HttpDiagnosticsRepository(
                loaded.endpointUrl,
                DiagnosticHttpTransport(uploadTransportFactory(), loaded.endpointUrl, recorder),
            ),
            recorder,
        ).execute()
    }

    private fun recordTap(
        loaded: WidgetSettings?,
        cell: CellKey,
        tappedAt: Instant,
        request: SelectionRequest,
        queuedTaps: Int,
        workId: String?,
    ) {
        recorderFor(loaded).record(
            DiagnosticEventType.TAP_RECEIVED,
            tapReceivedDetail(tappedAt, cell, request, queuedTaps, workId),
        )
    }

    private fun recordWorkStarted(tappedAt: Instant, run: WorkRun) {
        val delayMillis = Duration.between(tappedAt, clock()).toMillis()
        recorderFor(settings.read()).record(
            DiagnosticEventType.WORK_STARTED,
            workStartedDetail(tappedAt, delayMillis, run.runAttemptCount, run.readNetwork()),
        )
    }

    private fun isPending(cell: CellKey, tappedAt: Instant): Boolean = synchronized(WidgetStateLock) {
        storedQueue(clock()).any { it.workEnqueued && it.cell == cell && it.acceptedAt == tappedAt }
    }

    private fun hasOlderPending(tappedAt: Instant): Boolean = synchronized(WidgetStateLock) {
        storedQueue(clock()).any { it.workEnqueued && it.acceptedAt.isBefore(tappedAt) }
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
        publishSnapshot(settings.read(), settle { entries -> entries.withSent(cell, tappedAt, clock()) })
    }

    private fun publishInFlight() {
        val current = snapshots.read()
        publishSnapshot(settings.read(), current.copy(statusRequest = current.sending))
    }

    private fun publishSettled(update: (List<QueuedTap>) -> List<QueuedTap> = { it }) {
        publishSnapshot(settings.read(), settle(update))
    }

    private fun publishSnapshot(settings: WidgetSettings?, snapshot: WidgetSnapshot) {
        recordStatusIfChanged(settings, snapshot)
        publish(settings, snapshot)
    }

    private fun recordStatusIfChanged(settings: WidgetSettings?, snapshot: WidgetSnapshot) {
        val changedText = synchronized(WidgetStateLock) {
            val text = describeStatus(snapshot, clock())
            if (text == store.read(LAST_WIDGET_STATUS_KEY)) {
                null
            } else {
                store.write(LAST_WIDGET_STATUS_KEY, text)
                text
            }
        }
        changedText?.let { recorderFor(settings).record(DiagnosticEventType.WIDGET_STATUS, it) }
    }

    /** Writes the confirmed snapshot and returns the one to publish, which shows the projected selection. */
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
        val confirmed = current.copy(outcome = outcome, sending = last?.request, statusRequest = head?.request)
        snapshots.write(confirmed)
        confirmed.copy(
            selection = projectSelection(current.selection, shown),
            pendingTapAt = head?.acceptedAt,
            sentAt = head?.sentAt,
            oldestUnsentTapAt = head?.takeIf { it.waiting }?.acceptedAt,
            queuedTapCount = shown.size,
        )
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
        val timer = RequestTimer()
        return HttpSelectionRepository(
            current.endpointUrl,
            DiagnosticHttpTransport(transportFactory(), current.endpointUrl, recorder, timer),
            recorder,
            timer,
        )
    }
}

private fun List<QueuedTap>.indexOfTap(cell: CellKey, tappedAt: Instant): Int =
    indexOfFirst { it.cell == cell && it.acceptedAt == tappedAt }

private fun List<QueuedTap>.withoutTap(cell: CellKey, tappedAt: Instant): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) this else filterIndexed { position, _ -> position != index }
}

private fun List<QueuedTap>.withWaiting(cell: CellKey, tappedAt: Instant): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) this else mapIndexed { position, entry ->
        if (position == index) entry.copy(waiting = true, sentAt = null) else entry
    }
}

private fun List<QueuedTap>.withSent(cell: CellKey, tappedAt: Instant, sentAt: Instant): List<QueuedTap> {
    val index = indexOfTap(cell, tappedAt)
    return if (index < 0) this else mapIndexed { position, entry ->
        if (position == index) entry.copy(waiting = false, sentAt = sentAt) else entry
    }
}

private fun QueuedTap.isOwnTap(ownCell: CellKey?, ownTappedAt: Instant?): Boolean =
    ownCell != null && ownTappedAt != null && cell == ownCell && acceptedAt == ownTappedAt

private fun QueuedTap.isReconciledAway(states: Map<UUID, WorkInfo.State>, now: Instant): Boolean {
    val id = workId ?: return false
    val state = states[id]
    return if (state == null) {
        acceptedAt.isBefore(now.minus(WORK_ABSENT_GRACE_PERIOD))
    } else {
        state in FINISHED_WORK_STATES
    }
}

private fun List<QueuedTap>.displayed(now: Instant): List<QueuedTap> = filterNot { entry ->
    entry.workEnqueued && entry.acceptedAt.isBefore(now.minus(PENDING_TAP_DISPLAY_MAX_AGE))
}

private fun projectSelection(confirmed: Selection?, pending: List<QueuedTap>): Selection? =
    pending.fold(confirmed) { _, entry ->
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
            if (position == index) entry.copy(workEnqueued = true, waiting = true, workId = null, sentAt = null) else entry
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
