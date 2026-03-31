package com.bontecou.syncmd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.AppShell
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Injected so we can forward the OAuth token before the ViewModel is ready. */
    @Inject
    lateinit var authManager: GitHubAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle a cold-start deep link (app wasn't running when the OAuth callback fired)
        handleOAuthIntent(intent)

        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val appSettings by settingsViewModel.appSettings.collectAsState()

            SyncMdTheme(darkTheme = appSettings.isDarkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppShell()
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
    }

    // ─── Deep link handling ──────────────────────────────────────────────────

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
            val token = data.getQueryParameter(GitHubAuthManager.QUERY_TOKEN)
            if (!token.isNullOrEmpty()) {
                authManager.handleCallback(token)
            }
        }
    }
}
