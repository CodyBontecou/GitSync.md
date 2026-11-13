package com.bontecou.syncmd

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/** Emits only counts/ownership flags; no preference or credential value leaves the app sandbox. */
class FixtureStateAuditTest {
    @Test fun inspectOnlyNonsecretFixtureMetadataAfterInterruptedInstrumentation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureAudit") == "true")
        val values = context.getSharedPreferences("sync_md_prefs", 0).all
        val paths = values.filterKeys { it.startsWith("repo_") && it.endsWith("_path") }.values.filterIsInstance<String>()
        val fixtureCount = paths.count { it.contains("/callback-transport-") }
        val managedCount = File(context.filesDir, "repos").walkTopDown().maxDepth(4).filter { it.isDirectory && File(it, ".git").isDirectory }.count()
        val checkoutPrivate = context.filesDir.listFiles()?.count { it.name.startsWith("checkout-protection-") } ?: 0
        @Suppress("DEPRECATION") val checkoutMedia = context.externalMediaDirs.filterNotNull().sumOf { folder ->
            folder.listFiles()?.count { it.name.startsWith("checkout-protection-") } ?: 0
        }
        val statusPrivate = context.filesDir.listFiles()?.count { it.name.startsWith("status-metadata-") } ?: 0
        @Suppress("DEPRECATION") val statusMedia = context.externalMediaDirs.filterNotNull().sumOf { folder ->
            folder.listFiles()?.count { it.name.startsWith("status-metadata-") } ?: 0
        }
        val liveOverviews=context.filesDir.listFiles()?.count { it.name.startsWith("overview-live-") } ?: 0
        val migrationPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("url-migration-") } ?: 0
        @Suppress("DEPRECATION") val migrationMedia=context.externalMediaDirs.filterNotNull().sumOf { directory -> directory.listFiles()?.count { it.name.startsWith("url-migration-") } ?: 0 }
        val navigationPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("parity-navigation-") } ?: 0
        val renamePrivate=context.filesDir.listFiles()?.count { it.name.startsWith("rename-storage-") } ?: 0
        @Suppress("DEPRECATION") val renameMedia=context.externalMediaDirs.filterNotNull().sumOf { directory -> directory.listFiles()?.count { it.name.startsWith("rename-storage-") } ?: 0 }
        val rebasePrivate=context.filesDir.listFiles()?.count { it.name.startsWith("rebase-device-") } ?: 0
        @Suppress("DEPRECATION") val rebaseMedia=context.externalMediaDirs.filterNotNull().sumOf { directory -> File(directory,"repos").listFiles()?.count { it.name.startsWith("rebase-device-") } ?: 0 }
        val grantPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("callback-grant-") } ?: 0
        @Suppress("DEPRECATION") val grantMedia=context.externalMediaDirs.filterNotNull().sumOf { directory -> directory.listFiles()?.count { it.name.startsWith("callback-grant-move-") } ?: 0 }
        val replayPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("replay-checkout-") } ?: 0
        @Suppress("DEPRECATION") val replayMedia=context.externalMediaDirs.filterNotNull().sumOf { directory -> directory.listFiles()?.count { it.name.startsWith("replay-checkout-") } ?: 0 }
        val observationPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("foreground-watch-") } ?: 0
        @Suppress("DEPRECATION") val observationMedia=context.externalMediaDirs.filterNotNull().sumOf { directory -> directory.listFiles()?.count { it.name.startsWith("foreground-watch-") } ?: 0 }
        val movePrivate=context.filesDir.listFiles()?.count { it.name.startsWith("move-test-") || it.name.startsWith("move-cold-start-") } ?: 0
        @Suppress("DEPRECATION") val moveMedia=context.externalMediaDirs.filterNotNull().sumOf { directory ->
            File(directory,"repos").listFiles()?.count { it.name.startsWith("cold-start-") || it.name.startsWith("vault-") } ?: 0
        }
        val cloneCompletionPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("clone-completion-") } ?: 0
        val preparationPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("obsidian-preparation-cold-") } ?: 0
        val pendingPreparations=context.getSharedPreferences("obsidian_connections", 0).all.values.count {
            it is String && runCatching { org.json.JSONObject(it).optInt("schema") == 2 }.getOrDefault(false)
        }
        val clonePublicationPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("clone-publication-cold-") } ?: 0
        val clonePublicationInventory=com.bontecou.syncmd.storage.clonePublications(context).inspect()
        val clonePublicationPending=clonePublicationInventory.count { it.publication?.registeredDate == null }
        val clonePublicationDamaged=clonePublicationInventory.count { it.damaged }
        val cloneReviewPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("clone-recovery-review-") } ?: 0
        val cloneCatalogValues=values.filterKeys { it.startsWith(com.bontecou.syncmd.services.repositories.CloneCatalogProvenance.PREFIX) }
        val invalidCloneCatalog=cloneCatalogValues.count { (key,value) -> com.bontecou.syncmd.services.repositories.CloneCatalogProvenance.decode(key,value)==null }
        val cloneCatalogPrivate=context.filesDir.listFiles()?.count { it.name.startsWith("clone-catalog-provenance-") || it.name.startsWith("clone-catalog-cold-") } ?: 0
        val backgroundPullPrivate = context.filesDir.listFiles()?.count { it.name.startsWith("pull-all-background-") } ?: 0
        val backgroundPullCold = if (File(context.noBackupFilesDir, "pull-all-background-cold-proof.json").exists()) 1 else 0
        val backgroundPullPreferences = File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.count {
            it.name.endsWith(".xml") && (it.name.startsWith("pull-all-cold-") || it.name.startsWith("pull-all-store-") || it.name.startsWith("pull-all-native-"))
        } ?: 0
        val backgroundPullActive = if (com.bontecou.syncmd.automation.PullAllBackgroundRuns.isActive()) 1 else 0
        val callbackInterruptionWorkspaces=context.filesDir.listFiles()?.count { it.name.startsWith("callback-interruption-") } ?: 0
        val callbackInterruptionMarker=if(File(context.noBackupFilesDir,"callback-interruption-proof.json").exists()) 1 else 0
        val cloneLanguageWorkspaces=context.filesDir.listFiles()?.count { it.name.startsWith("clone-language-") } ?: 0
        val cloneLanguagePreferences=File(context.applicationInfo.dataDir,"shared_prefs").listFiles()?.count { it.name.startsWith("clone-language-") } ?: 0
        val fileUiCacheWorkspaces=context.cacheDir.listFiles()?.count { it.name.startsWith("file-ui-") } ?: 0
        @Suppress("DEPRECATION")
        val fileEditorMediaWorkspaces=context.externalMediaDirs.filterNotNull().sumOf { folder -> folder.listFiles()?.count { it.name.startsWith("file-editor-test-") } ?: 0 }
        val upstreamWorkspaces=context.filesDir.listFiles()?.count { it.name.startsWith("upstream-native-") } ?: 0
        val upstreamPreferences=File(context.applicationInfo.dataDir,"shared_prefs").listFiles()?.count { it.name.startsWith("upstream-native-") } ?: 0
        val accountLanguageWorkspaces=context.filesDir.listFiles()?.count { it.name.startsWith("account-language-") } ?: 0
        val accountLanguagePreferences=File(context.applicationInfo.dataDir,"shared_prefs").listFiles()?.count { it.name.startsWith("account-language-") } ?: 0
        val authorRequestWorkspaces=context.filesDir.listFiles()?.count { it.name.startsWith("author-request-") } ?: 0
        val authorRequestPreferences=File(context.applicationInfo.dataDir,"shared_prefs").listFiles()?.count { it.name.startsWith("author-request-") } ?: 0
        val githubAccountWorkspaces=context.filesDir.listFiles()?.count { it.name.startsWith("github-accounts-test-") } ?: 0
        val githubTestPreferencesNeedingCleanup=File(context.applicationInfo.dataDir,"shared_prefs").listFiles()?.count { file ->
            val scoped=listOf("github-vm-test-","github-accounts-test-","github-session-test-").any { file.name.startsWith(it) }
            scoped && (file.name.endsWith(".xml.bak") || (file.name.endsWith(".xml") &&
                context.getSharedPreferences(file.name.removeSuffix(".xml"),0).all.isNotEmpty()))
        } ?: 0
        val info = "catalog_count=${values["repository_count"] ?: 0}; owned_callback_fixture_count=$fixtureCount; managed_working_copy_count=$managedCount; pending_private_recovery_files=${context.noBackupFilesDir.listFiles()?.count { it.name.startsWith("native-fixture-recovery-") } ?: 0}; owned_private_checkout_workspaces=$checkoutPrivate; owned_media_checkout_workspaces=$checkoutMedia; owned_private_status_workspaces=$statusPrivate; owned_media_status_workspaces=$statusMedia; owned_live_overview_workspaces=$liveOverviews; owned_private_url_migration_workspaces=$migrationPrivate; owned_media_url_migration_workspaces=$migrationMedia; owned_private_navigation_workspaces=$navigationPrivate; owned_private_rename_storage_workspaces=$renamePrivate; owned_media_rename_storage_workspaces=$renameMedia; owned_private_rebase_workspaces=$rebasePrivate; owned_media_rebase_workspaces=$rebaseMedia; owned_private_callback_grant_workspaces=$grantPrivate; owned_media_callback_grant_workspaces=$grantMedia; owned_private_replay_checkout_workspaces=$replayPrivate; owned_media_replay_checkout_workspaces=$replayMedia; owned_private_observation_workspaces=$observationPrivate; owned_media_observation_workspaces=$observationMedia; owned_private_move_workspaces=$movePrivate; owned_media_move_workspaces=$moveMedia; owned_private_clone_completion_workspaces=$cloneCompletionPrivate; owned_private_obsidian_preparation_workspaces=$preparationPrivate; pending_obsidian_preparations=$pendingPreparations; owned_private_clone_publication_workspaces=$clonePublicationPrivate; pending_clone_publications=$clonePublicationPending; damaged_clone_receipts=$clonePublicationDamaged; owned_clone_recovery_review_workspaces=$cloneReviewPrivate; clone_catalog_receipt_count=${cloneCatalogValues.size}; invalid_clone_catalog_receipt_count=$invalidCloneCatalog; owned_clone_catalog_workspaces=$cloneCatalogPrivate; active_observation_roots=${com.bontecou.syncmd.observation.RepositoryObservations.activeRootCount()}; active_observation_watches=${com.bontecou.syncmd.observation.RepositoryObservations.watcherCount()}; owned_background_pull_workspaces=$backgroundPullPrivate; pending_background_pull_cold_proof=$backgroundPullCold; active_background_pull_runs=$backgroundPullActive; owned_background_pull_preferences=$backgroundPullPreferences; owned_callback_interruption_workspaces=$callbackInterruptionWorkspaces; pending_callback_interruption_marker=$callbackInterruptionMarker; owned_clone_language_workspaces=$cloneLanguageWorkspaces; owned_clone_language_preferences=$cloneLanguagePreferences; owned_file_ui_cache_workspaces=$fileUiCacheWorkspaces; owned_media_file_editor_workspaces=$fileEditorMediaWorkspaces; owned_upstream_workspaces=$upstreamWorkspaces; owned_upstream_preferences=$upstreamPreferences; owned_account_language_workspaces=$accountLanguageWorkspaces; owned_account_language_preferences=$accountLanguagePreferences; owned_author_request_workspaces=$authorRequestWorkspaces; owned_author_request_preferences=$authorRequestPreferences; owned_github_account_workspaces=$githubAccountWorkspaces; owned_github_test_preferences_needing_cleanup=$githubTestPreferencesNeedingCleanup\n"
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", info) })
    }
}
