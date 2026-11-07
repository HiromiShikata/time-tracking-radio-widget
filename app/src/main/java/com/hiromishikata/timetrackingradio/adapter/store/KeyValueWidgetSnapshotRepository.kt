package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshotRepository
import java.time.Instant

class KeyValueWidgetSnapshotRepository(private val store: KeyValueStore) : WidgetSnapshotRepository {
    override fun read(): WidgetSnapshot = WidgetSnapshot(
        selection = readSelection(),
        fetchedAt = store.read(FETCHED_AT_KEY)?.let(Instant::parse),
        outcome = readOutcome(),
        sending = readSending(),
    )

    override fun write(snapshot: WidgetSnapshot) {
        writeSelection(snapshot.selection)
        store.write(FETCHED_AT_KEY, snapshot.fetchedAt?.toString())
        writeOutcome(snapshot.outcome)
        writeSending(snapshot.sending)
    }

    private fun readSelection(): Selection? {
        val project = store.read(SELECTION_PROJECT_KEY) ?: return null
        val activity = ActivityKind.fromCode(store.read(SELECTION_ACTIVITY_KEY).orEmpty()) ?: return null
        val since = store.read(SELECTION_SINCE_KEY)?.let(Instant::parse) ?: return null
        return Selection(CellKey(project, activity), since)
    }

    private fun writeSelection(selection: Selection?) {
        store.write(SELECTION_PROJECT_KEY, selection?.cell?.project)
        store.write(SELECTION_ACTIVITY_KEY, selection?.cell?.activity?.code)
        store.write(SELECTION_SINCE_KEY, selection?.since?.toString())
    }

    private fun readOutcome(): WidgetOutcome = when (store.read(OUTCOME_KEY)) {
        OUTCOME_UNREACHABLE -> WidgetOutcome.Unreachable
        OUTCOME_REJECTED -> WidgetOutcome.Rejected(store.read(OUTCOME_MESSAGE_KEY).orEmpty())
        else -> WidgetOutcome.Settled
    }

    private fun writeOutcome(outcome: WidgetOutcome) {
        store.write(
            OUTCOME_KEY,
            when (outcome) {
                WidgetOutcome.Settled -> null
                WidgetOutcome.Unreachable -> OUTCOME_UNREACHABLE
                is WidgetOutcome.Rejected -> OUTCOME_REJECTED
            },
        )
        store.write(OUTCOME_MESSAGE_KEY, (outcome as? WidgetOutcome.Rejected)?.message)
    }

    private fun readSending(): SelectionRequest? = when (store.read(SENDING_KIND_KEY)) {
        SENDING_SELECT -> {
            val project = store.read(SENDING_PROJECT_KEY)
            val activity = ActivityKind.fromCode(store.read(SENDING_ACTIVITY_KEY).orEmpty())
            if (project != null && activity != null) SelectionRequest.Select(CellKey(project, activity)) else null
        }
        SENDING_CLEAR -> SelectionRequest.Clear
        else -> null
    }

    private fun writeSending(request: SelectionRequest?) {
        store.write(
            SENDING_KIND_KEY,
            when (request) {
                null -> null
                is SelectionRequest.Select -> SENDING_SELECT
                SelectionRequest.Clear -> SENDING_CLEAR
            },
        )
        store.write(SENDING_PROJECT_KEY, (request as? SelectionRequest.Select)?.cell?.project)
        store.write(SENDING_ACTIVITY_KEY, (request as? SelectionRequest.Select)?.cell?.activity?.code)
    }

    private companion object {
        const val SELECTION_PROJECT_KEY = "selection_project"
        const val SELECTION_ACTIVITY_KEY = "selection_activity"
        const val SELECTION_SINCE_KEY = "selection_since"
        const val FETCHED_AT_KEY = "fetched_at"
        const val OUTCOME_KEY = "outcome"
        const val OUTCOME_MESSAGE_KEY = "outcome_message"
        const val OUTCOME_UNREACHABLE = "unreachable"
        const val OUTCOME_REJECTED = "rejected"
        const val SENDING_KIND_KEY = "sending_kind"
        const val SENDING_PROJECT_KEY = "sending_project"
        const val SENDING_ACTIVITY_KEY = "sending_activity"
        const val SENDING_SELECT = "select"
        const val SENDING_CLEAR = "clear"
    }
}
