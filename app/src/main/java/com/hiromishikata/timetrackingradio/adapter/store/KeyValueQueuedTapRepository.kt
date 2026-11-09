package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

data class QueuedTap(
    val cell: CellKey,
    val acceptedAt: Instant,
    val request: SelectionRequest,
    val workEnqueued: Boolean,
    val waiting: Boolean,
)

class KeyValueQueuedTapRepository(private val store: KeyValueStore) {
    fun readAll(): List<QueuedTap> {
        val body = store.read(QUEUE_KEY) ?: return emptyList()
        return Json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val entry = element.jsonObject
            if (!entry.containsKey(REQUEST_PROJECT_FIELD)) return@mapNotNull null
            QueuedTap(
                readCell(entry.getValue(PROJECT_FIELD).jsonPrimitive.content, entry.getValue(ACTIVITY_FIELD)),
                Instant.parse(entry.getValue(ACCEPTED_AT_FIELD).jsonPrimitive.content),
                readRequest(entry),
                entry[WORK_ENQUEUED_FIELD]?.jsonPrimitive?.booleanOrNull ?: false,
                entry[WAITING_FIELD]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    fun write(entries: List<QueuedTap>) {
        val body = if (entries.isEmpty()) {
            null
        } else {
            buildJsonArray {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put(PROJECT_FIELD, entry.cell.project)
                            put(ACTIVITY_FIELD, entry.cell.activity.code)
                            put(ACCEPTED_AT_FIELD, entry.acceptedAt.toString())
                            val requestCell = (entry.request as? SelectionRequest.Select)?.cell
                            put(REQUEST_PROJECT_FIELD, requestCell?.project)
                            put(REQUEST_ACTIVITY_FIELD, requestCell?.activity?.code)
                            put(WORK_ENQUEUED_FIELD, entry.workEnqueued)
                            put(WAITING_FIELD, entry.waiting)
                        },
                    )
                }
            }.toString()
        }
        store.write(QUEUE_KEY, body)
    }

    private fun readRequest(entry: JsonObject): SelectionRequest {
        val project = entry.getValue(REQUEST_PROJECT_FIELD).jsonPrimitive.contentOrNull
            ?: return SelectionRequest.Clear
        return SelectionRequest.Select(readCell(project, entry.getValue(REQUEST_ACTIVITY_FIELD)))
    }

    private fun readCell(project: String, activity: JsonElement): CellKey = CellKey(
        project,
        requireNotNull(ActivityKind.fromCode(activity.jsonPrimitive.content)) { "queued tap has an unknown activity" },
    )

    private companion object {
        const val QUEUE_KEY = "queued_taps"
        const val PROJECT_FIELD = "project"
        const val ACTIVITY_FIELD = "activity"
        const val ACCEPTED_AT_FIELD = "accepted_at"
        const val REQUEST_PROJECT_FIELD = "request_project"
        const val REQUEST_ACTIVITY_FIELD = "request_activity"
        const val WORK_ENQUEUED_FIELD = "work_enqueued"
        const val WAITING_FIELD = "waiting"
    }
}
