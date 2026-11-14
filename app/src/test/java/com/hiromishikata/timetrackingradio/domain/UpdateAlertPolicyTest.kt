package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UpdateAlertPolicyTest {
    @Test
    fun decidesTheAnnouncementFromTheStoredAlertAndTheNotificationState() {
        data class Case(
            val name: String,
            val version: String,
            val stored: StoredUpdateAlert?,
            val notificationActive: Boolean?,
            val expected: AnnounceOutcome,
        )
        val now = Instant.parse("2026-11-14T12:00:00Z")
        fun alertedAgo(hours: Long, minutes: Long = 0): Instant = now.minus(Duration.ofHours(hours).plusMinutes(minutes))
        val cases = listOf(
            Case("nothing stored alerts", "1.0.2", null, false, AnnounceOutcome.ALERTED),
            Case("a different version alerts even while the older one shows", "1.0.2", StoredUpdateAlert("1.0.1", alertedAgo(0)), true, AnnounceOutcome.ALERTED),
            Case("the same version still showing re-posts silently", "1.0.2", StoredUpdateAlert("1.0.2", alertedAgo(7)), true, AnnounceOutcome.SILENT_UPDATE),
            Case("the same version still showing within six hours re-posts silently", "1.0.2", StoredUpdateAlert("1.0.2", alertedAgo(1)), true, AnnounceOutcome.SILENT_UPDATE),
            Case("dismissed 59 minutes ago is skipped", "1.0.2", StoredUpdateAlert("1.0.2", alertedAgo(0, 59)), false, AnnounceOutcome.SKIPPED_RECENTLY_DISMISSED),
            Case("dismissed exactly six hours ago alerts again", "1.0.2", StoredUpdateAlert("1.0.2", alertedAgo(6)), false, AnnounceOutcome.ALERTED),
            Case("dismissed seven hours ago alerts again", "1.0.2", StoredUpdateAlert("1.0.2", alertedAgo(7)), false, AnnounceOutcome.ALERTED),
            Case("unknown active state is treated as not showing", "1.0.2", StoredUpdateAlert("1.0.2", alertedAgo(2)), null, AnnounceOutcome.SKIPPED_RECENTLY_DISMISSED),
            Case("unknown active state after six hours alerts again", "1.0.2", StoredUpdateAlert("1.0.2", alertedAgo(6)), null, AnnounceOutcome.ALERTED),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, decideUpdateAlert(case.version, case.stored, case.notificationActive, now))
        }
    }
}
