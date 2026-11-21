package com.bontecou.syncmd.review

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [ReviewPromptStore] on private SharedPreferences. */
class PrefsReviewPromptStore(context: Context) : ReviewPromptStore {
    private val prefs = context.applicationContext.getSharedPreferences("review_prompt", Context.MODE_PRIVATE)
    override fun getInt(key: String): Int = prefs.getInt(key, 0)
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun put(values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                else -> error("Unsupported review prompt value for $key")
            }
        }
        editor.apply()
    }
}

/**
 * Shows Google Play's in-app review flow for [ReviewPrompts.requests], from the activity's own lifecycle.
 *
 * Each request waits [DELAY_MILLIS] (so the sync's result can settle on screen), then re-checks: the activity
 * is RESUMED and has window focus (a Compose Dialog, the paywall dialog, a modal bottom sheet and Play's
 * purchase sheet all take focus away), no single-vault sync is running ([syncRunning]), the app was installed
 * from Google Play, and the policy still allows a prompt. It asks Play for a ReviewInfo, re-checks the
 * lifecycle and focus, records the prompt, and launches the flow. Play decides whether the card actually
 * appears (it has its own quota) and never tells the app.
 *
 * Not detectable here: inline Snackbars and Toasts (no app-wide overlay state exists).
 */
class ReviewPromptHost(
    private val activity: ComponentActivity,
    private val prompts: ReviewPrompts,
    private val syncRunning: () -> Boolean,
    private val manager: ReviewManager = ReviewManagerFactory.create(activity),
) {
    fun attach() {
        activity.lifecycleScope.launch {
            // Collecting only while RESUMED: requests made in the background are dropped, and pausing
            // cancels a pending delay.
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                prompts.requests.collect { show() }
            }
        }
    }

    private suspend fun show() {
        delay(DELAY_MILLIS)
        if (!ready()) return
        val info = try { manager.requestReview() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return }
        if (!ready()) return
        prompts.promptLaunched()
        try { manager.launchReview(activity, info) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }

    private fun ready(): Boolean =
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            activity.hasWindowFocus() &&
            !syncRunning() &&
            installedFromPlay(activity) &&
            prompts.isEligible()

    companion object {
        const val DELAY_MILLIS = 2_000L
        const val PLAY_STORE = "com.android.vending"

        fun installedFromPlay(context: Context): Boolean = runCatching {
            val pm = context.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
            }
            installer == PLAY_STORE
        }.getOrDefault(false)
    }
}
