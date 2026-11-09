package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.UnstampedSelectionRepository
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.domain.applySelectionResponse
import com.hiromishikata.timetrackingradio.domain.markUnreachable
import com.hiromishikata.timetrackingradio.domain.resolveTapRequest
import java.time.Instant

class WidgetSelectionUpdateUseCase(
    private val selections: SelectionRepository,
    private val snapshots: WidgetSnapshotRepository,
    private val afterSendingStarted: () -> Unit = {},
    private val clock: () -> Instant,
) {
    fun execute(tapped: CellKey, tappedAt: Instant): WidgetSnapshot =
        execute(resolveTapRequest(snapshots.read().selection, tapped), tappedAt)

    fun execute(request: SelectionRequest, tappedAt: Instant): WidgetSnapshot =
        send(request) { selections.update(request, tappedAt) }

    fun executeUnstamped(tapped: CellKey, unstamped: UnstampedSelectionRepository): WidgetSnapshot {
        val request = resolveTapRequest(snapshots.read().selection, tapped)
        return send(request) { unstamped.updateUnstamped(request) }
    }

    private fun send(request: SelectionRequest, post: () -> SelectionResponse): WidgetSnapshot {
        val previous = snapshots.read()
        val sending = previous.copy(sending = request)
        snapshots.write(sending)
        val finished = try {
            afterSendingStarted()
            applySelectionResponse(post(), sending, clock()).copy(sending = null)
        } catch (failure: NetworkUnavailableException) {
            snapshots.write(sending.copy(outcome = WidgetOutcome.WaitingForNetwork))
            throw failure
        } catch (failure: CommunicationFailedException) {
            markUnreachable(sending).copy(sending = null)
        } catch (failure: Throwable) {
            snapshots.write(sending.copy(sending = null))
            throw failure
        }
        snapshots.write(finished)
        return finished
    }
}
