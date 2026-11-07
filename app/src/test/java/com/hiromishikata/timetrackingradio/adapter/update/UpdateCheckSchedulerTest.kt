package com.hiromishikata.timetrackingradio.adapter.update

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.hiromishikata.timetrackingradio.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateCheckSchedulerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun schedulesOneSixHourCheckAndKeepsTheExistingSchedule() {
        UpdateCheckScheduler.schedule(context)
        UpdateCheckScheduler.schedule(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("app-update-check").get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertEquals(TimeUnit.HOURS.toMillis(6), infos[0].periodicityInfo!!.repeatIntervalMillis)
    }
}
