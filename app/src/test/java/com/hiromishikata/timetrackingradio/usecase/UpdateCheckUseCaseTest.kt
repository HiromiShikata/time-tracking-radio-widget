package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import com.hiromishikata.timetrackingradio.domain.UpdateAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckUseCaseTest {
    private class RecordingAnnouncer : UpdateAnnouncer {
        val announced = mutableListOf<Pair<SemanticVersion, String>>()

        override fun announce(version: SemanticVersion, apkDownloadUrl: String) {
            announced += version to apkDownloadUrl
        }
    }

    private val apkUrl = "https://example.com/app.apk"

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
            val status = UpdateCheckUseCase(releases, announcer).execute(case.installed)
            assertEquals(case.name, case.expectedStatus, status)
            assertEquals(case.name, case.expectedAnnounced, announcer.announced)
            assertEquals(case.name, case.expectedReads, releases.readCount)
        }
    }

    @Test
    fun anUnreadableInstalledVersionFailsBeforeAnyReleaseIsRead() {
        val releases = FakeReleaseRepository()
        val announcer = RecordingAnnouncer()
        val status = UpdateCheckUseCase(releases, announcer).execute("0.0.0-dev")
        assertEquals(UpdateCheckStatus.FAILED, status)
        assertEquals(0, releases.readCount)
        assertEquals(emptyList<Pair<SemanticVersion, String>>(), announcer.announced)
    }
}
