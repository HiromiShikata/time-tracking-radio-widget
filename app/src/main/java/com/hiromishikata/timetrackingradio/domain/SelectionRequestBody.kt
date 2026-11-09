package com.hiromishikata.timetrackingradio.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val tappedAtFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun encodeSelectionRequest(request: SelectionRequest, tappedAt: Instant): String {
    val tappedAtField = "\"tappedAt\":\"${tappedAtFormat.format(tappedAt)}\""
    return when (request) {
        is SelectionRequest.Select -> {
            val project = request.cell.project.toJsonString()
            "{\"project\":$project,\"activity\":\"${request.cell.activity.code}\",$tappedAtField}"
        }
        SelectionRequest.Clear -> "{\"project\":null,\"activity\":null,$tappedAtField}"
    }
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
