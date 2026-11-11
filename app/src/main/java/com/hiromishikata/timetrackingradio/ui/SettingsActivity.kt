package com.hiromishikata.timetrackingradio.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.adapter.http.DiagnosticHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.DIAGNOSTICS_UPLOAD_TIMEOUT_MILLIS
import com.hiromishikata.timetrackingradio.adapter.http.HttpDiagnosticsRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpSelectionRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.adapter.update.UpdateCheckScheduler
import com.hiromishikata.timetrackingradio.adapter.update.UpdateNotifier
import com.hiromishikata.timetrackingradio.adapter.update.updateDiagnosticRecorder
import com.hiromishikata.timetrackingradio.adapter.widget.SelectionWidgetProvider
import com.hiromishikata.timetrackingradio.adapter.widget.WidgetWorkScheduler
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.ConnectionCheckResult
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.UpdateSetupStep
import com.hiromishikata.timetrackingradio.domain.updateSetupSteps
import com.hiromishikata.timetrackingradio.domain.describeFailure
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSettingsProblem
import com.hiromishikata.timetrackingradio.domain.WidgetSettingsValidation
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.domain.renderDiagnosticLog
import com.hiromishikata.timetrackingradio.usecase.DiagnosticUploadUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetConnectionCheckUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSettingsReadUseCase
import com.hiromishikata.timetrackingradio.usecase.WidgetSettingsUpdateUseCase
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import com.hiromishikata.timetrackingradio.domain.RequestTimer

class SettingsActivity : Activity() {
    private val store by lazy {
        SharedPreferencesKeyValueStore(getSharedPreferences(SETTINGS_PREFERENCES_NAME, MODE_PRIVATE))
    }
    private val settingsRepository by lazy { KeyValueWidgetSettingsRepository(store) }
    private val diagnosticLog by lazy { KeyValueDiagnosticLogRepository(store) }
    private val widgetId by lazy {
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setResult(RESULT_CANCELED)
        kickPendingTaps()
        val endpointInput = findViewById<EditText>(R.id.endpoint_input)
        val projectsInput = findViewById<EditText>(R.id.projects_input)
        WidgetSettingsReadUseCase(settingsRepository).execute()?.let { settings ->
            endpointInput.setText(settings.endpointUrl)
            projectsInput.setText(settings.projects.joinToString("\n"))
        }
        val statusDetailsSwitch = findViewById<Switch>(R.id.show_status_details_switch)
        statusDetailsSwitch.isChecked = settingsRepository.readShowStatusDetails()
        statusDetailsSwitch.setOnCheckedChangeListener { _, checked ->
            settingsRepository.writeShowStatusDetails(checked)
            SelectionWidgetProvider.requestUpdate(this)
        }
        findViewById<Button>(R.id.save_button).setOnClickListener {
            save(endpointInput.text.toString(), projectsInput.text.toString(), statusDetailsSwitch.isChecked)
        }
        findViewById<Button>(R.id.copy_log_button).setOnClickListener { copyLog() }
        findViewById<TextView>(R.id.log_text).movementMethod = ScrollingMovementMethod.getInstance()
        findViewById<Button>(R.id.install_permission_button).setOnClickListener { openUnknownAppSourcesSettings() }
        findViewById<Button>(R.id.update_notification_guide_button).setOnClickListener { openNotificationSettings() }
        renderLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            currentSetupSteps().contains(UpdateSetupStep.RequestNotificationPermission)
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        enqueueForegroundUpdateCheck()
        val installStepVisibility = if (currentSetupSteps().contains(UpdateSetupStep.OpenUnknownAppSources)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.install_permission_guide).visibility = installStepVisibility
        findViewById<Button>(R.id.install_permission_button).visibility = installStepVisibility
        val notificationStepVisibility =
            if (currentSetupSteps().contains(UpdateSetupStep.RequestNotificationPermission)) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.update_notification_guide_button).visibility = notificationStepVisibility
    }

    private fun enqueueForegroundUpdateCheck() {
        try {
            UpdateCheckScheduler.enqueueForegroundCheckIfDue(this, Instant.now())
        } catch (failure: Exception) {
            val detail = describeFailure(failure)
            Log.e(TAG, "foreground update check was not enqueued: $detail")
            updateDiagnosticRecorder(this).record(DiagnosticEventType.EXCEPTION, detail)
        }
    }

    private fun kickPendingTaps() {
        try {
            WidgetWorkScheduler.kickPendingTaps(this)
        } catch (failure: Exception) {
            val detail = describeFailure(failure)
            Log.e(TAG, "pending taps were not kicked: $detail")
            updateDiagnosticRecorder(this).record(DiagnosticEventType.EXCEPTION, detail)
        }
    }

    private fun currentSetupSteps(): List<UpdateSetupStep> = updateSetupSteps(
        UpdateNotifier(this).notificationsPermitted(),
        packageManager.canRequestPackageInstalls(),
        Build.VERSION.SDK_INT,
    )

    private fun openUnknownAppSourcesSettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun save(endpointUrl: String, projectsText: String, showStatusDetails: Boolean) {
        when (val validation = WidgetSettingsUpdateUseCase(settingsRepository).execute(endpointUrl, projectsText, showStatusDetails)) {
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
                recorderFor(validation.settings).record(
                    DiagnosticEventType.SETTINGS_SAVED,
                    "projects=${validation.settings.projects.size}",
                )
                checkConnection(validation.settings)
            }
        }
        renderLog()
    }

    /** Runs on the background executor; the screen stays open so the outcome can be read. */
    /**
     * Runs on the background executor. Every failure is caught here, recorded and shown, so an exception can
     * never escape the executor; the screen stays open.
     */
    private fun checkConnection(settings: WidgetSettings) {
        findViewById<TextView>(R.id.connection_status).text = getString(R.string.connection_checking)
        backgroundExecutor.execute {
            try {
                runConnectionCheck(settings)
            } catch (failure: Exception) {
                val detail = describeFailure(failure, endpointKeySecrets(settings.endpointUrl))
                recorderFor(settings).record(DiagnosticEventType.EXCEPTION, detail)
                runOnUiThread {
                    findViewById<TextView>(R.id.connection_status).text =
                        connectionCheckMessage(this, ConnectionCheckResult.Failed(detail))
                    renderLog()
                }
            }
        }
    }

    private fun runConnectionCheck(settings: WidgetSettings) {
        val recorder = recorderFor(settings)
        val timer = RequestTimer()
        val result = WidgetConnectionCheckUseCase(
            HttpSelectionRepository(
                settings.endpointUrl,
                DiagnosticHttpTransport(transportFactory(), settings.endpointUrl, recorder, timer),
                recorder,
                timer,
            ),
            recorder,
            endpointKeySecrets(settings.endpointUrl),
        ).execute()
        DiagnosticUploadUseCase(
            diagnosticLog,
            HttpDiagnosticsRepository(
                settings.endpointUrl,
                DiagnosticHttpTransport(uploadTransportFactory(), settings.endpointUrl, recorder),
            ),
            recorder,
        ).execute()
        runOnUiThread {
            findViewById<TextView>(R.id.connection_status).text = connectionCheckMessage(this, result)
            renderLog()
        }
    }

    private fun recorderFor(settings: WidgetSettings): DiagnosticRecorder =
        DiagnosticRecorder(diagnosticLog, endpointKeySecrets(settings.endpointUrl)) { Instant.now() }

    private fun renderLog() {
        val entries = diagnosticLog.readAll()
        findViewById<TextView>(R.id.log_text).text =
            if (entries.isEmpty()) getString(R.string.log_empty) else renderDiagnosticLog(entries)
    }

    private fun copyLog() {
        val text = renderDiagnosticLog(diagnosticLog.readAll())
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.log_title), text),
        )
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun problemMessage(problem: WidgetSettingsProblem): Int = when (problem) {
        WidgetSettingsProblem.ENDPOINT_EMPTY -> R.string.problem_endpoint_empty
        WidgetSettingsProblem.ENDPOINT_NOT_HTTPS -> R.string.problem_endpoint_not_https
        WidgetSettingsProblem.ENDPOINT_NOT_A_URI -> R.string.problem_endpoint_not_a_uri
        WidgetSettingsProblem.PROJECTS_EMPTY -> R.string.problem_projects_empty
        WidgetSettingsProblem.PROJECT_NAME_DUPLICATED -> R.string.problem_project_duplicated
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1
        private const val TAG = "SettingsActivity"

        /** Replaceable by tests; production uses the platform HTTP transport and one background thread. */
        var transportFactory: () -> HttpTransport = { HttpUrlConnectionTransport() }
        var uploadTransportFactory: () -> HttpTransport = {
            HttpUrlConnectionTransport(DIAGNOSTICS_UPLOAD_TIMEOUT_MILLIS)
        }
        var backgroundExecutor: Executor = Executors.newSingleThreadExecutor()
    }
}
