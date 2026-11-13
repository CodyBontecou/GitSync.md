package com.bontecou.syncmd.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.bontecou.syncmd.BuildConfig

object SupportLinks {
    const val email = "cody@isolated.tech"
    const val community = "https://discord.gg/RaQYS4t6gn"
    fun feedbackIntent(): Intent {
        val subject = Uri.encode("GitSync.md Android feedback")
        val body = Uri.encode("\n\n---\nApp: GitSync.md ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid API: ${Build.VERSION.SDK_INT}\nDevice: ${Build.MODEL}\n")
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email?subject=$subject&body=$body"))
    }
    fun communityIntent() = Intent(Intent.ACTION_VIEW, Uri.parse(community))
}
