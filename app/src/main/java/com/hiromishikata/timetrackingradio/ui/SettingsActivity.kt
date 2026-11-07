package com.hiromishikata.timetrackingradio.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.adapter.widget.SelectionWidgetProvider
import com.hiromishikata.timetrackingradio.domain.WidgetSettingsProblem
import com.hiromishikata.timetrackingradio.domain.WidgetSettingsValidation
import com.hiromishikata.timetrackingradio.usecase.WidgetSettingsReadUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSettingsUpdateUseCase

class SettingsActivity : Activity() {
    private val settingsRepository by lazy {
        KeyValueWidgetSettingsRepository(
            SharedPreferencesKeyValueStore(getSharedPreferences(SETTINGS_PREFERENCES_NAME, MODE_PRIVATE)),
        )
    }
    private val widgetId by lazy {
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setResult(RESULT_CANCELED)
        val endpointInput = findViewById<EditText>(R.id.endpoint_input)
        val projectsInput = findViewById<EditText>(R.id.projects_input)
        WidgetSettingsReadUseCase(settingsRepository).execute()?.let { settings ->
            endpointInput.setText(settings.endpointUrl)
            projectsInput.setText(settings.projects.joinToString("\n"))
        }
        findViewById<Button>(R.id.save_button).setOnClickListener {
            save(endpointInput.text.toString(), projectsInput.text.toString())
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun save(endpointUrl: String, projectsText: String) {
        when (val validation = WidgetSettingsUpdateUseCase(settingsRepository).execute(endpointUrl, projectsText)) {
            is WidgetSettingsValidation.Invalid -> Toast.makeText(
                this,
                validation.problems.joinToString("\n") { getString(problemMessage(it)) },
                Toast.LENGTH_LONG,
            ).show()
            is WidgetSettingsValidation.Valid -> {
                SelectionWidgetProvider.requestUpdate(this)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                }
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun problemMessage(problem: WidgetSettingsProblem): Int = when (problem) {
        WidgetSettingsProblem.ENDPOINT_EMPTY -> R.string.problem_endpoint_empty
        WidgetSettingsProblem.ENDPOINT_NOT_HTTPS -> R.string.problem_endpoint_not_https
        WidgetSettingsProblem.PROJECTS_EMPTY -> R.string.problem_projects_empty
        WidgetSettingsProblem.PROJECT_NAME_DUPLICATED -> R.string.problem_project_duplicated
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}
