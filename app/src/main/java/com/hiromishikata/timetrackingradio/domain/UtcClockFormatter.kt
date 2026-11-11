package com.hiromishikata.timetrackingradio.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val utcHourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

private val utcHourMinuteSecond: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

fun formatUtcHourMinute(instant: Instant): String = utcHourMinute.format(instant)

fun formatUtcHourMinuteSecond(instant: Instant): String = utcHourMinuteSecond.format(instant)
