package com.hiromishikata.timetrackingradio.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val tappedAtFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun encodeSelectionRequest(request: SelectionRequest, tappedAt: Instant): String =
    selectionRequestBody(request, "\"tappedAt\":\"${tappedAtFormat.format(tappedAt)}\"")

fun encodeUnstampedSelectionRequest(request: SelectionRequest): String = selectionRequestBody(request, null)

private fun selectionRequestBody(request: SelectionRequest, tappedAtField: String?): String {
    val fields = when (request) {
        is SelectionRequest.Select -> listOf(
            "\"project\":${request.cell.project.toJsonString()}",
            "\"activity\":\"${request.cell.activity.code}\"",
        )
        SelectionRequest.Clear -> listOf("\"project\":null", "\"activity\":null")
    }
    return (fields + listOfNotNull(tappedAtField)).joinToString(prefix = "{", postfix = "}", separator = ",")
}

private fun String.toJsonString(): String = buildString {
    append('"')
    for (character in this@toJsonString) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
