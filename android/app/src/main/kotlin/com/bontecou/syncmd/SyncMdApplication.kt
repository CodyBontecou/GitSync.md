package com.bontecou.syncmd

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch

/**
 * Gitsync.md application class with Hilt support.
 */
@HiltAndroidApp
class SyncMdApplication : Application() {
    private var automationLocales: String? = null
    @javax.inject.Inject lateinit var sshTrust: com.bontecou.syncmd.security.SshHostKeyStore
    @javax.inject.Inject lateinit var lfsEndpoints: com.bontecou.syncmd.security.LfsEndpointStore
    @javax.inject.Inject lateinit var diagnostics: com.bontecou.syncmd.diagnostics.AppDiagnostics
    @javax.inject.Inject lateinit var appUnlock: com.bontecou.syncmd.billing.AppUnlock
    override fun onCreate() {
        super.onCreate()
        automationLocales = resources.configuration.locales.toLanguageTags()
        com.bontecou.syncmd.services.diagnostics.Diagnostics.install(diagnostics.store::append)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launchStartupDiagnostics()
        val locations = com.bontecou.syncmd.storage.RepositoryLocations(this)
        com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.installAccessCheck(locations::requireRepositoryAccess)
        val publications = com.bontecou.syncmd.storage.AndroidFolderPublications.privateStore(this)
        com.bontecou.syncmd.services.publication.FolderPublicationGate.install(publications::requireAvailable)
        com.bontecou.syncmd.services.git.SshTrust.install(sshTrust::verify)
        com.bontecou.syncmd.services.git.lfs.LfsEndpointTrust.install(lfsEndpoints::verify, lfsEndpoints::headers, lfsEndpoints::verifySshHttp)
        com.bontecou.syncmd.push.PushRuntime.ready.complete(Unit)
        // Installs that already hold vaults when the paywall arrives stay unlocked.
        val savedCount = getSharedPreferences("sync_md_prefs", MODE_PRIVATE).all["repository_count"]
        appUnlock.start(hasExistingVaults = savedCount != null && savedCount != 0)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.bontecou.syncmd.automation.AutomationLocaleJobs.enqueue(this@SyncMdApplication)
            runCatching { com.bontecou.syncmd.push.PushSyncJobs.maintain(this@SyncMdApplication) }
            runCatching { com.bontecou.syncmd.background.BackgroundSyncJobs.schedule(this@SyncMdApplication) }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val locales = newConfig.locales.toLanguageTags()
        if (automationLocales != locales) {
            automationLocales = locales
            com.bontecou.syncmd.automation.AutomationLocaleJobs.enqueue(this, changed = true)
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchStartupDiagnostics() = launch {
        com.bontecou.syncmd.services.diagnostics.Diagnostics.record(
            com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.APP,
            com.bontecou.syncmd.services.diagnostics.DiagnosticOutcome.COMPLETED)
    }
}
