package com.hiromishikata.timetrackingradio.adapter.diagnostics

import android.content.Context
import android.os.Build
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AppStartDiagnosticsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun log() = KeyValueDiagnosticLogRepository(
        SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)),
    )

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
    fun recordAppStartWritesOneAppStartedEvent() {
        recordAppStart(context)
        assertEquals(listOf(DiagnosticEventType.APP_STARTED), log().readAll().map { it.type })
    }
}
