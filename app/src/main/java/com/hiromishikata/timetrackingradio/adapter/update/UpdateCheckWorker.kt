package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hiromishikata.timetrackingradio.adapter.http.HttpReleaseRepository
import com.hiromishikata.timetrackingradio.adapter.http.HttpUrlConnectionTransport
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.usecase.UpdateCheckStatus
import com.hiromishikata.timetrackingradio.usecase.UpdateCheckUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val installedName = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName.orEmpty()
        val useCase = UpdateCheckUseCase(releaseRepositoryFactory(), UpdateNotifier(applicationContext))
        when (useCase.execute(installedName)) {
            UpdateCheckStatus.COMPLETED -> Result.success()
            UpdateCheckStatus.RETRY -> {
                Log.w(TAG, "update check failed; it will be retried")
                Result.retry()
            }
            UpdateCheckStatus.FAILED -> {
                Log.e(TAG, "installed version is not x.y.z: $installedName")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/HiromiShikata/time-tracking-radio-widget/releases/latest"

        var releaseRepositoryFactory: () -> ReleaseRepository = {
            HttpReleaseRepository(LATEST_RELEASE_URL, HttpUrlConnectionTransport())
        }
    }
}
