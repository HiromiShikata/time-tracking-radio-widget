package com.hiromishikata.timetrackingradio.adapter.diagnostics

import android.content.Context
import android.os.Build
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.recordAppStarted
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AppStartInfoReaderTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun theStartInfoNamesTheInstalledVersionAndTheDevice() {
        val info = readAppStartInfo(context)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(installed.versionName.orEmpty(), info.versionName)
        assertEquals(Build.VERSION.SDK_INT, info.sdkInt)
        assertEquals(Build.MANUFACTURER, info.manufacturer)
        assertEquals(Build.MODEL, info.model)
    }

    @Test
    fun theStartInfoIsRecordedAsOneAppStartedEvent() {
        val log = FakeDiagnosticLogRepository()
        recordAppStarted(DiagnosticRecorder(log, emptySet()) { Instant.EPOCH }, readAppStartInfo(context))
        assertEquals(listOf(DiagnosticEventType.APP_STARTED), log.recorded.map { it.type })
    }
}
