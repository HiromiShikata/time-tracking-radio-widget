package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import java.time.Instant

fun updateDiagnosticRecorder(context: Context): DiagnosticRecorder {
    val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
    val endpointUrl = KeyValueWidgetSettingsRepository(store).read()?.endpointUrl.orEmpty()
    return DiagnosticRecorder(KeyValueDiagnosticLogRepository(store), endpointKeySecrets(endpointUrl)) { Instant.now() }
}
