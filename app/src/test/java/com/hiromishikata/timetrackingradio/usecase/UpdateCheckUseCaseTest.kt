package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.LastUpdateCheckRepository
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import com.hiromishikata.timetrackingradio.domain.UpdateAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UpdateCheckUseCaseTest {
    private class RecordingAnnouncer(private val permitted: Boolean = true) : UpdateAnnouncer {
        val announced = mutableListOf<Pair<SemanticVersion, String>>()

        override fun notificationsPermitted(): Boolean = permitted

        override fun announce(version: SemanticVersion, apkDownloadUrl: String): Boolean {
            announced += version to apkDownloadUrl
            return permitted
        }
    }

    private class RecordingDiagnostics : DiagnosticSink {
        val recorded = mutableListOf<Pair<DiagnosticEventType, String>>()

        override fun record(type: DiagnosticEventType, detail: String) {
            recorded += type to detail
        }
    }

    private class InMemoryLastUpdateCheckRepository : LastUpdateCheckRepository {
        var stored: Instant? = null
            private set

        override fun read(): Instant? = stored

        override fun write(at: Instant) {
            stored = at
        }
    }

    private val apkUrl = "https://example.com/app.apk"
    private val checkedAt = Instant.parse("2026-11-09T02:19:03Z")

    @Test
    fun mapsEveryOutcomeToAStatusAndAnnouncesOnlyNewerPackages() {
        data class Case(
            val name: String,
            val installed: String,
            val release: () -> LatestRelease,
            val expectedStatus: UpdateCheckStatus,
            val expectedAnnounced: List<Pair<SemanticVersion, String>>,
            val expectedReads: Int,
        )
        val cases = listOf(
            Case(
                "newer package is announced",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl) },
                UpdateCheckStatus.COMPLETED,
                listOf(SemanticVersion(1, 0, 2) to apkUrl),
                1,
            ),
            Case(
                "same version is not announced",
                "1.0.2",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl) },
                UpdateCheckStatus.COMPLETED,
                emptyList(),
                1,
            ),
            Case(
                "newer release without an APK is not announced",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), null) },
                UpdateCheckStatus.COMPLETED,
                emptyList(),
                1,
            ),
            Case(
                "failed release check is retried",
                "1.0.1",
                { throw CommunicationFailedException("down") },
                UpdateCheckStatus.RETRY,
                emptyList(),
                1,
            ),
        )
        cases.forEach { case ->
            val releases = FakeReleaseRepository(case.release)
            val announcer = RecordingAnnouncer()
            val status = UpdateCheckUseCase(releases, announcer, RecordingDiagnostics(), InMemoryLastUpdateCheckRepository()) { checkedAt }
                .execute(case.installed)
            assertEquals(case.name, case.expectedStatus, status)
            assertEquals(case.name, case.expectedAnnounced, announcer.announced)
            assertEquals(case.name, case.expectedReads, releases.readCount)
        }
    }

    @Test
    fun anUnreadableInstalledVersionFailsBeforeAnyReleaseIsRead() {
        val releases = FakeReleaseRepository()
        val announcer = RecordingAnnouncer()
        val status = UpdateCheckUseCase(releases, announcer, RecordingDiagnostics(), InMemoryLastUpdateCheckRepository()) { checkedAt }
            .execute("0.0.0-dev")
        assertEquals(UpdateCheckStatus.FAILED, status)
        assertEquals(0, releases.readCount)
        assertEquals(emptyList<Pair<SemanticVersion, String>>(), announcer.announced)
    }

    @Test
    fun aCheckRecordsOneUpdateCheckEntryForEachOutcome() {
        data class Case(
            val name: String,
            val installed: String,
            val release: () -> LatestRelease,
            val notificationsPermitted: Boolean,
            val expectedDetail: Regex,
        )
        fun exact(text: String) = Regex(Regex.escape(text))
        val cases = listOf(
            Case(
                "up to date",
                "1.0.2",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl) },
                true,
                exact("installed=1.0.2, latest=v1.0.2, decision=up_to_date, notifications_permitted=true, notified=false"),
            ),
            Case(
                "newer and notified",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl) },
                true,
                exact("installed=1.0.1, latest=v1.0.2, decision=newer_available, notifications_permitted=true, notified=true"),
            ),
            Case(
                "newer and notifications not permitted",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl) },
                false,
                exact("installed=1.0.1, latest=v1.0.2, decision=newer_available, notifications_permitted=false, notified=false"),
            ),
            Case(
                "release check failed",
                "1.0.1",
                { throw CommunicationFailedException("down") },
                true,
                Regex("^installed=1\\.0\\.1, latest=none, decision=check_failed, reason=.*down, notifications_permitted=true, notified=false$"),
            ),
        )
        cases.forEach { case ->
            val diagnostics = RecordingDiagnostics()
            UpdateCheckUseCase(
                FakeReleaseRepository(case.release),
                RecordingAnnouncer(case.notificationsPermitted),
                diagnostics,
                InMemoryLastUpdateCheckRepository(),
            ) { checkedAt }.execute(case.installed)
            assertEquals(case.name, 1, diagnostics.recorded.size)
            val (type, detail) = diagnostics.recorded.single()
            assertEquals(case.name, DiagnosticEventType.UPDATE_CHECK, type)
            assertTrue("${case.name}: $detail", case.expectedDetail.matches(detail))
        }
    }

    @Test
    fun aCompletedCheckWritesTheTimestampAndAFailedCheckDoesNot() {
        data class Case(
            val name: String,
            val release: () -> LatestRelease,
            val expectedStatus: UpdateCheckStatus,
            val expectedStored: Instant?,
        )
        val cases = listOf(
            Case(
                "completed check",
                { LatestRelease(SemanticVersion(1, 0, 1), null) },
                UpdateCheckStatus.COMPLETED,
                checkedAt,
            ),
            Case(
                "retried check",
                { throw CommunicationFailedException("down") },
                UpdateCheckStatus.RETRY,
                null,
            ),
        )
        cases.forEach { case ->
            val lastChecked = InMemoryLastUpdateCheckRepository()
            val status = UpdateCheckUseCase(FakeReleaseRepository(case.release), RecordingAnnouncer(), RecordingDiagnostics(), lastChecked) { checkedAt }
                .execute("1.0.1")
            assertEquals(case.name, case.expectedStatus, status)
            assertEquals(case.name, case.expectedStored, lastChecked.read())
        }
    }
}
