package com.bontecou.syncmd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.AppShell
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Injected so we can forward the OAuth token before the ViewModel is ready. */
    @Inject
    lateinit var authManager: GitHubAuthManager
    @Inject
    lateinit var appUnlock: com.bontecou.syncmd.billing.AppUnlock

    private val settingsViewModel: SettingsViewModel by viewModels()
    private val callbacks: com.bontecou.syncmd.ui.viewmodels.RepositoryCallbackViewModel by viewModels()
    private val obsidian: com.bontecou.syncmd.ui.viewmodels.ObsidianViewModel by viewModels()
    private val pushSync: com.bontecou.syncmd.ui.viewmodels.PushSyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle a cold-start deep link (app wasn't running when the OAuth callback fired)
        handleOAuthIntent(intent)
        handleCallbackIntent(intent, savedInstanceState == null)
        handleObsidianIntent(intent, savedInstanceState == null)
        handlePushLinkIntent(intent, savedInstanceState == null)

        setContent {
            SyncMdTheme(darkTheme = com.bontecou.syncmd.ui.theme.rememberAppDarkTheme(this)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    androidx.compose.runtime.CompositionLocalProvider(com.bontecou.syncmd.ui.util.LocalAppUnlock provides appUnlock) {
                        AppShell(settingsViewModel = settingsViewModel)
                    }
                }
            }
        }
    }

    /**
     * Called when the activity is already running and a new intent arrives — i.e.
     * when the OAuth redirect fires while the app is in the foreground or background
     * (thanks to android:launchMode="singleTask").
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
        handleCallbackIntent(intent, true)
        handleObsidianIntent(intent, true)
        handlePushLinkIntent(intent, true)
    }
    override fun onStop() {
        com.bontecou.syncmd.automation.RepositoryShortcutRequests.clear()
        if (!isChangingConfigurations) {
            callbacks.cancel(background = true)
            com.bontecou.syncmd.automation.RepositoryCallbackRequests.clear()
            obsidian.stop()
            com.bontecou.syncmd.obsidian.ObsidianRequests.clear()
            pushSync.stopPull()
            com.bontecou.syncmd.push.PushSyncRequests.clear()
        }
        super.onStop()
    }
    override fun onResume() {
        super.onResume(); callbacks.foreground(true); obsidian.foreground(true)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (com.bontecou.syncmd.background.BackgroundSyncStore(this@MainActivity).read().active)
                    com.bontecou.syncmd.background.BackgroundSyncJobs.now(this@MainActivity)
            }
        }
    }
    override fun onPause() { callbacks.foreground(false); obsidian.foreground(false); super.onPause() }

    // ─── Deep link handling ──────────────────────────────────────────────────
    private fun handlePushLinkIntent(incoming: Intent?, freshDelivery: Boolean) {
        val data = incoming?.data ?: return
        if (data.scheme != "syncmd" || data.host != "github-app") return
        incoming.data = null; setIntent(incoming)
        if (freshDelivery && incoming.action == Intent.ACTION_VIEW && incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0)
            com.bontecou.syncmd.push.PushLinkCallbacks.offer(data.toString())
    }
    private fun handleObsidianIntent(incoming: Intent?, freshDelivery: Boolean) {
        val data = incoming?.data ?: return
        if (data.scheme != "syncmd" || data.host != "obsidian") return
        incoming.data = null; setIntent(incoming)
        if (freshDelivery && incoming.action == Intent.ACTION_VIEW && incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0)
            com.bontecou.syncmd.obsidian.ObsidianRequests.offer(data.toString())
    }

    private fun handleCallbackIntent(incoming: Intent?, freshDelivery: Boolean) {
        val data = incoming?.data ?: return
        if (data.scheme != "syncmd" || data.host != "x-callback-url") return
        incoming.data = null
        setIntent(incoming)
        if (freshDelivery && incoming.action == Intent.ACTION_VIEW)
            com.bontecou.syncmd.automation.RepositoryCallbackRequests.offer(data.toString())
    }

    /**
     * Parses `syncmd://auth?token=XXX` and stores the token via [GitHubAuthManager].
     * The token StateFlow update propagates to every observer (ViewModels / Compose UI)
     * automatically.
     */
    private fun handleOAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == GitHubAuthManager.CALLBACK_SCHEME &&
            data.host == GitHubAuthManager.CALLBACK_HOST
        ) {
            // Do not retain a token-bearing callback in the activity's launch intent.
            intent.data = null
            setIntent(intent)
            authManager.acceptOAuthCallback(data)
        }
    }
}
