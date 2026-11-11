package com.hiromishikata.timetrackingradio.adapter.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.hiromishikata.timetrackingradio.domain.AppStartInfo

fun readAppStartInfo(context: Context): AppStartInfo {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return AppStartInfo(
        versionName = info.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(info),
        sdkInt = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
    )
}
