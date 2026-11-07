package com.hiromishikata.timetrackingradio.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val utcHourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

fun formatUtcHourMinute(instant: Instant): String = utcHourMinute.format(instant)
