package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.domain.applySelectionResponse
import com.hiromishikata.timetrackingradio.domain.markUnreachable
import java.time.Instant

class WidgetSnapshotReadUseCase(
    private val selections: SelectionRepository,
    private val snapshots: WidgetSnapshotRepository,
    private val clock: () -> Instant,
) {
    fun execute(): WidgetSnapshot {
        val previous = snapshots.read()
        val next = try {
            applySelectionResponse(selections.read(), previous, clock()).copy(
                savedAt = previous.savedAt,
                roundTripMillis = previous.roundTripMillis,
            )
        } catch (failure: CommunicationFailedException) {
            markUnreachable(previous)
        } catch (failure: Throwable) {
            snapshots.write(previous.copy(sending = null))
            throw failure
        }
        snapshots.write(next)
        return next
    }
}
