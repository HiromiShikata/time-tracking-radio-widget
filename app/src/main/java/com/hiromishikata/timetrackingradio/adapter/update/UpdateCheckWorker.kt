package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hiromishikata.timetrackingradio.adapter.http.DiagnosticHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpReleaseRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.adapter.widget.deviceStateExceptionSuffixes
import com.hiromishikata.timetrackingradio.domain.ApkDownloadRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.usecase.UpdateCheckStatus
import com.hiromishikata.timetrackingradio.usecase.UpdateCheckUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val installedName = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName.orEmpty()
        val store = SharedPreferencesKeyValueStore(
            applicationContext.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
        val endpointUrl = KeyValueWidgetSettingsRepository(store).read()?.endpointUrl.orEmpty()
        val recorder = DiagnosticRecorder(
            KeyValueDiagnosticLogRepository(store),
            endpointKeySecrets(endpointUrl),
            deviceStateExceptionSuffixes(applicationContext),
        ) { Instant.now() }
        val useCase = UpdateCheckUseCase(
            releaseRepositoryFactory(endpointUrl, recorder),
            downloaderFactory(applicationContext.cacheDir),
            UpdateNotifier(applicationContext),
            recorder,
            UpdateCheckScheduler.lastCheckRepository(applicationContext),
        ) { Instant.now() }
        when (useCase.execute(installedName)) {
            UpdateCheckStatus.COMPLETED -> Result.success()
            UpdateCheckStatus.RETRY -> {
                Log.w(TAG, "update check failed; a retry is scheduled")
                UpdateCheckScheduler.enqueueCheckRetry(applicationContext, inputData.getInt(KEY_RETRY_ATTEMPT, 0) + 1)
                Result.success()
            }
            UpdateCheckStatus.RETRY_DOWNLOAD -> {
                Log.w(TAG, "update download failed for a network reason; a retry is scheduled")
                UpdateCheckScheduler.enqueueDownloadRetry(applicationContext, inputData.getInt(KEY_RETRY_ATTEMPT, 0) + 1)
                Result.success()
            }
            UpdateCheckStatus.FAILED -> {
                Log.e(TAG, "installed version is not x.y.z: $installedName")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val KEY_RETRY_ATTEMPT = "retryAttempt"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/HiromiShikata/time-tracking-radio-widget/releases/latest"

        var downloaderFactory: (File) -> ApkDownloadRepository = { ApkDownloader(it) }

        var releaseRepositoryFactory: (endpointUrl: String, diagnostics: DiagnosticSink) -> ReleaseRepository = { endpointUrl, diagnostics ->
            HttpReleaseRepository(
                LATEST_RELEASE_URL,
                DiagnosticHttpTransport(HttpUrlConnectionTransport(), endpointUrl, diagnostics),
            )
        }
    }
}
