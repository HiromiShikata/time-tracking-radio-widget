package com.hiromishikata.timetrackingradio.adapter.update

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.domain.describeFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateInstallActivity : Activity() {
    private lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apkUrl = intent.getStringExtra(EXTRA_APK_URL)
        if (apkUrl == null) {
            Log.e(TAG, "install activity started without an APK URL")
            finish()
            return
        }
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { fetcher.fetch(apkUrl, cacheDir) }
            }
            result.onSuccess { apk -> startInstaller(apk) }
                .onFailure { failure ->
                    Log.e(TAG, "update download failed: ${describeFailure(failure)}")
                    Toast.makeText(this@UpdateInstallActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
            finish()
        }
    }

    override fun onDestroy() {
        if (::scope.isInitialized) scope.cancel()
        super.onDestroy()
    }

    private fun startInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    companion object {
        const val EXTRA_APK_URL = "com.hiromishikata.timetrackingradio.extra.APK_URL"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val TAG = "UpdateInstallActivity"

        internal var fetcher: ApkFetcher = DefaultApkFetcher
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main
    }
}
