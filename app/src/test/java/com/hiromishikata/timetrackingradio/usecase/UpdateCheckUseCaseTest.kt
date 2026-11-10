package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.ApkDownloadException
import com.hiromishikata.timetrackingradio.domain.ApkDownloadRepository
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.LastUpdateCheckRepository
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import com.hiromishikata.timetrackingradio.domain.UpdateAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

private val downloadedApk = File("updates/app-update.apk")

class UpdateCheckUseCaseTest {
    private class CallOrder {
        val events = mutableListOf<String>()
    }

    private class FakeApkDownloads(
        private val order: CallOrder = CallOrder(),
        private val onDownload: (String, Long) -> File = { _, _ -> downloadedApk },
    ) : ApkDownloadRepository {
        val calls = mutableListOf<Pair<String, Long>>()
        val versions = mutableListOf<SemanticVersion>()

        override fun download(url: String, expectedSizeBytes: Long, version: SemanticVersion): File {
            order.events += "download"
            calls += url to expectedSizeBytes
            versions += version
            return onDownload(url, expectedSizeBytes)
        }
    }

    private class RecordingAnnouncer(
        private val order: CallOrder = CallOrder(),
        private val permitted: Boolean = true,
    ) : UpdateAnnouncer {
        val announced = mutableListOf<Pair<SemanticVersion, File>>()

        override fun notificationsPermitted(): Boolean = permitted

        override fun announce(version: SemanticVersion, apk: File): Boolean {
            order.events += "announce"
            announced += version to apk
            return permitted
        }
    }

    private class ThrowingAnnouncer(private val failure: RuntimeException) : UpdateAnnouncer {
        override fun notificationsPermitted(): Boolean = true

        override fun announce(version: SemanticVersion, apk: File): Boolean = throw failure
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
    private val apkSize = 4096L
    private val checkedAt = Instant.parse("2026-11-09T02:19:03Z")

    private fun useCase(
        releases: ReleaseRepository,
        downloads: ApkDownloadRepository = FakeApkDownloads(),
        announcer: UpdateAnnouncer = RecordingAnnouncer(),
        diagnostics: DiagnosticSink = RecordingDiagnostics(),
        lastChecked: LastUpdateCheckRepository = InMemoryLastUpdateCheckRepository(),
    ) = UpdateCheckUseCase(releases, downloads, announcer, diagnostics, lastChecked) { checkedAt }

    @Test
    fun mapsEveryOutcomeToAStatusAndAnnouncesOnlyNewerPackages() {
        data class Case(
            val name: String,
            val installed: String,
            val release: () -> LatestRelease,
            val expectedStatus: UpdateCheckStatus,
            val expectedAnnounced: List<Pair<SemanticVersion, File>>,
            val expectedReads: Int,
        )
        val cases = listOf(
            Case(
                "newer package is announced",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
                UpdateCheckStatus.COMPLETED,
                listOf(SemanticVersion(1, 0, 2) to downloadedApk),
                1,
            ),
            Case(
                "same version is not announced",
                "1.0.2",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
                UpdateCheckStatus.COMPLETED,
                emptyList(),
                1,
            ),
            Case(
                "newer release without an APK is not announced",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), null, null) },
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
            val status = useCase(releases, announcer = announcer).execute(case.installed)
            assertEquals(case.name, case.expectedStatus, status)
            assertEquals(case.name, case.expectedAnnounced, announcer.announced)
            assertEquals(case.name, case.expectedReads, releases.readCount)
        }
    }

    @Test
    fun anUnreadableInstalledVersionFailsBeforeAnyReleaseIsRead() {
        val releases = FakeReleaseRepository()
        val downloads = FakeApkDownloads()
        val announcer = RecordingAnnouncer()
        val status = useCase(releases, downloads, announcer).execute("0.0.0-dev")
        assertEquals(UpdateCheckStatus.FAILED, status)
        assertEquals(0, releases.readCount)
        assertEquals(emptyList<Pair<String, Long>>(), downloads.calls)
        assertEquals(emptyList<Pair<SemanticVersion, File>>(), announcer.announced)
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
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
                true,
                exact("installed=1.0.2, latest=v1.0.2, decision=up_to_date, download=none, notifications_permitted=true, notified=false"),
            ),
            Case(
                "newer and notified",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
                true,
                exact("installed=1.0.1, latest=v1.0.2, decision=newer_available, download=ok, notifications_permitted=true, notified=true"),
            ),
            Case(
                "newer and notifications not permitted",
                "1.0.1",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
                false,
                exact("installed=1.0.1, latest=v1.0.2, decision=newer_available, download=skipped, notifications_permitted=false, notified=false"),
            ),
            Case(
                "release check failed",
                "1.0.1",
                { throw CommunicationFailedException("down") },
                true,
                Regex("^installed=1\\.0\\.1, latest=none, decision=check_failed, download=none, reason=.*down, notifications_permitted=true, notified=false$"),
            ),
        )
        cases.forEach { case ->
            val diagnostics = RecordingDiagnostics()
            useCase(
                FakeReleaseRepository(case.release),
                announcer = RecordingAnnouncer(permitted = case.notificationsPermitted),
                diagnostics = diagnostics,
            ).execute(case.installed)
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
                { LatestRelease(SemanticVersion(1, 0, 1), null, null) },
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
            val status = useCase(FakeReleaseRepository(case.release), lastChecked = lastChecked).execute("1.0.1")
            assertEquals(case.name, case.expectedStatus, status)
            assertEquals(case.name, case.expectedStored, lastChecked.read())
        }
    }

    @Test
    fun aNonCommunicationFailureIsRecordedAndNotRethrown() {
        data class Case(
            val name: String,
            val release: () -> LatestRelease,
            val announcer: UpdateAnnouncer,
            val expectedDetailParts: List<String>,
        )
        val cases = listOf(
            Case(
                "release read throws",
                { throw IllegalStateException("bad json") },
                RecordingAnnouncer(),
                listOf("decision=check_failed", "bad json"),
            ),
            Case(
                "announce throws",
                { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
                ThrowingAnnouncer(RuntimeException("notification failed")),
                listOf("notified=false", "notification failed"),
            ),
        )
        cases.forEach { case ->
            val diagnostics = RecordingDiagnostics()
            val lastChecked = InMemoryLastUpdateCheckRepository()
            val status = useCase(
                FakeReleaseRepository(case.release),
                announcer = case.announcer,
                diagnostics = diagnostics,
                lastChecked = lastChecked,
            ).execute("1.0.1")
            assertEquals(case.name, UpdateCheckStatus.FAILED, status)
            assertEquals(case.name, 1, diagnostics.recorded.size)
            val (type, detail) = diagnostics.recorded.single()
            assertEquals(case.name, DiagnosticEventType.UPDATE_CHECK, type)
            case.expectedDetailParts.forEach { part ->
                assertTrue("${case.name}: $detail", detail.contains(part))
            }
            assertNull(case.name, lastChecked.read())
        }
    }

    @Test
    fun theApkIsDownloadedBeforeTheNotificationIsPosted() {
        val order = CallOrder()
        val downloads = FakeApkDownloads(order)
        val announcer = RecordingAnnouncer(order)
        val diagnostics = RecordingDiagnostics()
        val lastChecked = InMemoryLastUpdateCheckRepository()
        val status = useCase(
            FakeReleaseRepository { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
            downloads,
            announcer,
            diagnostics,
            lastChecked,
        ).execute("1.0.1")
        assertEquals(UpdateCheckStatus.COMPLETED, status)
        assertEquals(listOf("download", "announce"), order.events)
        assertEquals(listOf(apkUrl to apkSize), downloads.calls)
        assertEquals(listOf(SemanticVersion(1, 0, 2) to downloadedApk), announcer.announced)
        assertTrue(diagnostics.recorded.single().second.contains("download=ok"))
        assertEquals(checkedAt, lastChecked.read())
    }

    @Test
    fun theAnnouncedVersionIsPassedToTheDownload() {
        val downloads = FakeApkDownloads()
        val status = useCase(
            FakeReleaseRepository { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
            downloads,
        ).execute("1.0.1")
        assertEquals(UpdateCheckStatus.COMPLETED, status)
        assertEquals(listOf(SemanticVersion(1, 0, 2)), downloads.versions)
    }

    @Test
    fun aFailedDownloadIsRetriedWithoutANotification() {
        val downloads = FakeApkDownloads { _, _ -> throw ApkDownloadException("HTTP 503") }
        val announcer = RecordingAnnouncer()
        val diagnostics = RecordingDiagnostics()
        val lastChecked = InMemoryLastUpdateCheckRepository()
        val status = useCase(
            FakeReleaseRepository { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
            downloads,
            announcer,
            diagnostics,
            lastChecked,
        ).execute("1.0.1")
        assertEquals(UpdateCheckStatus.RETRY, status)
        assertEquals(emptyList<Pair<SemanticVersion, File>>(), announcer.announced)
        val detail = diagnostics.recorded.single().second
        assertTrue(detail, detail.contains("download=failed"))
        assertTrue(detail, detail.contains("reason=HTTP 503"))
        assertNull(lastChecked.read())
    }

    @Test
    fun aPermissionMissingCheckSkipsTheDownload() {
        val downloads = FakeApkDownloads()
        val announcer = RecordingAnnouncer(permitted = false)
        val diagnostics = RecordingDiagnostics()
        val lastChecked = InMemoryLastUpdateCheckRepository()
        val status = useCase(
            FakeReleaseRepository { LatestRelease(SemanticVersion(1, 0, 2), apkUrl, apkSize) },
            downloads,
            announcer,
            diagnostics,
            lastChecked,
        ).execute("1.0.1")
        assertEquals(UpdateCheckStatus.COMPLETED, status)
        assertEquals(emptyList<Pair<String, Long>>(), downloads.calls)
        assertEquals(emptyList<Pair<SemanticVersion, File>>(), announcer.announced)
        val detail = diagnostics.recorded.single().second
        assertTrue(detail, detail.contains("download=skipped"))
        assertTrue(detail, detail.contains("notified=false"))
        assertEquals(checkedAt, lastChecked.read())
    }
}
