package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = TestApplication::class)
class WidgetWorkBelowAndroid12Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)

    @Before
    fun setUp() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
    }

    @After
    fun tearDown() {
        restoreDefaultWidgetFactories()
    }

    @Test
    fun aTapIsSentOnAndroidBelowTwelve() {
        val transport = EchoSelectionTransport()
        val rendered = mutableListOf<WidgetSnapshot>()
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { transport }, { EchoSelectionTransport() }, publish = { _, snapshot -> rendered += snapshot })
        }
        WidgetWorkScheduler.enqueueTap(context, meeting)
        val work = advanceUniqueWork(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
        assertEquals("work state", WorkInfo.State.SUCCEEDED, work.state)
        assertEquals(
            listOf("project-a"),
            transport.requests.filter { it.method == "POST" }.map { selectedProjectOf(it) },
        )
    }

    @Test
    fun aDiagnosticsUploadSucceedsOnAndroidBelowTwelve() {
        WidgetWorkScheduler.enqueueDiagnosticsUpload(context)
        val work = advanceUniqueWork(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME)
        assertEquals("work state", WorkInfo.State.SUCCEEDED, work.state)
    }

    private fun advanceUniqueWork(context: Context, uniqueWorkName: String): WorkInfo {
        val deadline = System.currentTimeMillis() + 5_000
        while (uniqueWorkInfos(context, uniqueWorkName).isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "no work was enqueued under $uniqueWorkName" }
            Thread.sleep(10)
        }
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        uniqueWorkInfos(context, uniqueWorkName).forEach { info ->
            driver.setAllConstraintsMet(info.id)
            driver.setInitialDelayMet(info.id)
        }
        awaitWorkFinished(context, uniqueWorkName)
        return uniqueWorkInfos(context, uniqueWorkName).single()
    }
}
