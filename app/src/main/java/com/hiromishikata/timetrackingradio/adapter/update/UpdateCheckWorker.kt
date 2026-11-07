package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hiromishikata.timetrackingradio.adapter.http.HttpReleaseRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.parseSemanticVersion
import com.hiromishikata.timetrackingradio.usecase.ReleaseReadUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val installedName = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName.orEmpty()
        val installed = parseSemanticVersion(installedName)
        if (installed == null) {
            Log.e(TAG, "installed version is not x.y.z: $installedName")
            return@withContext Result.failure()
        }
        try {
            val latest = ReleaseReadUseCase(
                HttpReleaseRepository(LATEST_RELEASE_URL, HttpUrlConnectionTransport()),
            ).execute()
            val apkUrl = latest.apkDownloadUrl
            if (latest.version > installed && apkUrl != null) {
                UpdateNotifier(applicationContext).notifyAvailable(latest.version, apkUrl)
            }
            Result.success()
        } catch (failure: CommunicationFailedException) {
            Log.w(TAG, "update check failed", failure)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "UpdateCheckWorker"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/HiromiShikata/time-tracking-radio-widget/releases/latest"
    }
}
