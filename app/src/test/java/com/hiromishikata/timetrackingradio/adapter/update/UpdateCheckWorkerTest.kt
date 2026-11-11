package com.hiromishikata.timetrackingradio.adapter.update

import android.Manifest
import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hiromishikata.timetrackingradio.domain.ApkDownloadException
import com.hiromishikata.timetrackingradio.domain.ApkDownloadRepository
import org.robolectric.Shadows
import java.io.File
import com.hiromishikata.timetrackingradio.adapter.http.HttpReleaseRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import androidx.work.testing.TestListenableWorkerBuilder
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.widget.widgetPreferences
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateCheckWorkerTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        UpdateCheckWorker.releaseRepositoryFactory = { HttpReleaseRepository(UpdateCheckWorker.LATEST_RELEASE_URL, HttpUrlConnectionTransport()) }
        UpdateCheckWorker.downloaderFactory = { ApkDownloader(it) }
    }

    private fun runWorkerWith(repository: ReleaseRepository): ListenableWorker.Result {
        UpdateCheckWorker.releaseRepositoryFactory = { repository }
        val worker = TestListenableWorkerBuilder<UpdateCheckWorker>(context).build()
        return worker.startWork().get()
    }

    @Test
    fun aFailedReleaseCheckIsRetried() {
        val repository = object : ReleaseRepository {
            override fun readLatest(): LatestRelease = throw CommunicationFailedException("down")
        }
        assertTrue(runWorkerWith(repository) is ListenableWorker.Result.Retry)
    }

    @Test
    fun aDownloadFailedForANetworkReasonIsRetriedWithinFiveMinutesAndTheRetryIsRecorded() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateCheckWorker.releaseRepositoryFactory = {
            object : ReleaseRepository {
                override fun readLatest(): LatestRelease = LatestRelease(SemanticVersion(0, 0, 1), "https://example.com/app.apk", 4096L)
            }
        }
        UpdateCheckWorker.downloaderFactory = {
            object : ApkDownloadRepository {
                override fun download(url: String, expectedSizeBytes: Long, version: SemanticVersion): File =
                    throw ApkDownloadException("download failed: java.net.UnknownHostException", networkReason = true)
            }
        }
        val result = TestListenableWorkerBuilder<UpdateCheckWorker>(context).build().startWork().get()
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        val retry = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(UpdateCheckScheduler.DOWNLOAD_RETRY_UNIQUE_WORK_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, retry.state)
        val delay = retry.nextScheduleTimeMillis - System.currentTimeMillis()
        assertTrue("the download retry is $delay ms away, over five minutes", delay <= 300_000L + 1_000L)
        val recorded = KeyValueDiagnosticLogRepository(widgetPreferences(context)).readAll()
        assertTrue(
            "no retry_scheduled entry for the download retry",
            recorded.any {
                it.type == DiagnosticEventType.RETRY_SCHEDULED &&
                    it.detail.contains("kind=update_download") &&
                    it.detail.contains("next_delay_seconds=300")
            },
        )
    }

    @Test
    fun aCheckThatFindsNothingNewerSucceeds() {
        val repository = object : ReleaseRepository {
            override fun readLatest(): LatestRelease = LatestRelease(SemanticVersion(0, 0, 0), null, null)
        }
        assertTrue(runWorkerWith(repository) is ListenableWorker.Result.Success)
    }

    @Test
    fun aCheckWritesItsOutcomeToTheDiagnosticLog() {
        val repository = object : ReleaseRepository {
            override fun readLatest(): LatestRelease = LatestRelease(SemanticVersion(0, 0, 0), null, null)
        }
        runWorkerWith(repository)
        val entries = KeyValueDiagnosticLogRepository(widgetPreferences(context)).readAll()
            .filter { it.type == DiagnosticEventType.UPDATE_CHECK }
        assertEquals(1, entries.size)
        assertTrue(entries.single().detail, entries.single().detail.contains("decision=up_to_date"))
    }
}
