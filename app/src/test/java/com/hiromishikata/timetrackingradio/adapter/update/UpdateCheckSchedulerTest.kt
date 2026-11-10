package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateCheckSchedulerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-10T00:00:00Z")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun freshWorkAndLastCheckState() {
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun onDemandCheckInfos(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(ON_DEMAND_WORK_NAME).get()

    @Test
    fun schedulesOneSixHourCheckAndKeepsTheExistingSchedule() {
        UpdateCheckScheduler.schedule(context)
        UpdateCheckScheduler.schedule(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("app-update-check").get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertEquals(TimeUnit.HOURS.toMillis(6), infos[0].periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun aStaleOrMissingLastCheckEnqueuesOneOnDemandCheck() {
        data class Case(val name: String, val lastCheckedAt: Instant?)
        val cases = listOf(
            Case("no timestamp", null),
            Case("seven hours old", now.minus(Duration.ofHours(7))),
            Case("six hours and one second old", now.minus(Duration.ofHours(6)).minusSeconds(1)),
        )
        cases.forEach { case ->
            freshWorkAndLastCheckState()
            case.lastCheckedAt?.let { UpdateCheckScheduler.lastCheckRepository(context).write(it) }
            UpdateCheckScheduler.enqueueOnDemandIfStale(context, now)
            val infos = onDemandCheckInfos()
            assertEquals(case.name, 1, infos.size)
            assertEquals(case.name, WorkInfo.State.ENQUEUED, infos.single().state)
        }
    }

    @Test
    fun aRecentLastCheckEnqueuesNothing() {
        data class Case(val name: String, val lastCheckedAt: Instant)
        val cases = listOf(
            Case("one hour old", now.minus(Duration.ofHours(1))),
            Case("exactly six hours old", now.minus(Duration.ofHours(6))),
        )
        cases.forEach { case ->
            freshWorkAndLastCheckState()
            UpdateCheckScheduler.lastCheckRepository(context).write(case.lastCheckedAt)
            UpdateCheckScheduler.enqueueOnDemandIfStale(context, now)
            assertTrue(case.name, onDemandCheckInfos().isEmpty())
        }
    }

    @Test
    fun aMalformedStoredTimeEnqueuesOnDemandCheck() {
        freshWorkAndLastCheckState()
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("last_update_check_at", "not-a-time")
            .commit()
        UpdateCheckScheduler.enqueueOnDemandIfStale(context, now)
        val infos = onDemandCheckInfos()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    private companion object {
        const val ON_DEMAND_WORK_NAME = "app-update-check-once"
    }
}
