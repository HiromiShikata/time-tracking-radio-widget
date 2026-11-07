package com.hiromishikata.timetrackingradio.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

fun parseSelectionResponse(body: String): SelectionResponse {
    val root = parseJsonObject(body)
    return when (val result = root.stringField("result")) {
        "ok" -> SelectionResponse.Accepted(root.selectionField("selection"))
        "ng" -> SelectionResponse.Rejected(
            message = root.stringField("message"),
            reported = if (root.containsKey("selection")) {
                ReportedSelection.Reported(root.selectionField("selection"))
            } else {
                ReportedSelection.NotReported
            },
        )
        else -> throw MalformedResponseException("unknown result: $result")
    }
}

private fun JsonObject.selectionField(key: String): Selection? {
    val element = this[key] ?: throw MalformedResponseException("$key is missing")
    if (element == JsonNull) return null
    val selection = element as? JsonObject ?: throw MalformedResponseException("$key is not an object")
    val project = selection.stringField("project")
    if (project.isEmpty()) throw MalformedResponseException("project is empty")
    val activity = ActivityKind.fromCode(selection.stringField("activity"))
        ?: throw MalformedResponseException("activity is not work or meeting")
    val since = try {
        Instant.parse(selection.stringField("since"))
    } catch (failure: DateTimeParseException) {
        throw MalformedResponseException("since is not an ISO-8601 instant", failure)
    }
    return Selection(CellKey(project, activity), since)
}
