package com.hiromishikata.timetrackingradio.adapter.update

import androidx.work.ListenableWorker
import com.hiromishikata.timetrackingradio.adapter.http.HttpReleaseRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import androidx.work.testing.TestListenableWorkerBuilder
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import org.junit.After
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
    fun aCheckThatFindsNothingNewerSucceeds() {
        val repository = object : ReleaseRepository {
            override fun readLatest(): LatestRelease = LatestRelease(SemanticVersion(0, 0, 0), null)
        }
        assertTrue(runWorkerWith(repository) is ListenableWorker.Result.Success)
    }
}
