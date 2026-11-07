package com.hiromishikata.timetrackingradio.domain

enum class ActivityKind(val code: String) {
    WORK("work"),
    MEETING("meeting");

    companion object {
        fun fromCode(code: String): ActivityKind? = entries.firstOrNull { it.code == code }
    }
}
