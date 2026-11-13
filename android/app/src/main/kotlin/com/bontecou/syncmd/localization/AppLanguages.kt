package com.bontecou.syncmd.localization

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** The system owns persistence/recreation; opening preferences never changes the device language. */
object AppLanguages {
    fun settings(context: Context, sdk: Int = Build.VERSION.SDK_INT): Intent = if (sdk >= 33)
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}"))
    else Intent(Settings.ACTION_LOCALE_SETTINGS)
}
