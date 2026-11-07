package com.hiromishikata.timetrackingradio.domain

fun encodeSelectionRequest(request: SelectionRequest): String = when (request) {
    is SelectionRequest.Select ->
        "{\"project\":${request.cell.project.toJsonString()},\"activity\":\"${request.cell.activity.code}\"}"
    SelectionRequest.Clear -> "{\"project\":null,\"activity\":null}"
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
