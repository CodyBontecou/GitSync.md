package com.bontecou.syncmd.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.VaultOverview
import com.bontecou.syncmd.storage.VaultPreference
import com.bontecou.syncmd.storage.collectAll
import com.bontecou.syncmd.ui.components.RailChrome
import com.bontecou.syncmd.ui.components.VaultStatusState
import com.bontecou.syncmd.ui.components.rememberVaultSyncStatus
import com.bontecou.syncmd.ui.components.vaultAgo
import com.bontecou.syncmd.ui.screens.HomeVault
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.viewmodels.HomeViewModel
import com.bontecou.syncmd.ui.viewmodels.SavedRepository

internal fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        if (current is Activity) return null
        current = current.baseContext
    }
    return null
}

/**
 * Home's view model scoped to the activity, so Sync all started from the rail keeps reporting
 * while the user moves between Changes, Files, History and Git. Null outside a Hilt activity.
 */
@Composable
fun activityHomeViewModel(): HomeViewModel? {
    val activity = LocalContext.current.findComponentActivity() ?: return null
    if (activity !is dagger.hilt.internal.GeneratedComponentManagerHolder) return null
    return hiltViewModel(activity)
}

/** One lifecycle-bound status read per vault, like Home's, with the Home rules for "needs you" and "in sync". */
@Composable
internal fun rememberAdaptiveVaults(repositories: List<SavedRepository>, home: HomeViewModel?): List<HomeVault> {
    val preferences = home?.preferences?.collectAll()?.value.orEmpty()
    val syncAll = home?.syncAll?.collectAsState()?.value
    return repositories.map { repo ->
        key(repo.path) {
            val status by rememberVaultSyncStatus(repo.path)
            HomeVault(repo, preferences[repo.path] ?: VaultPreference(), status, null,
                syncAll?.results?.get(repo.path), syncAll?.current == repo.path)
        }
    }
}

/** The rail's Sync all (with every vault's commits to push) and the account avatar. */
@Composable
fun rememberRailChrome(repositories: List<SavedRepository>, avatarInitials: String?, onAccount: () -> Unit): RailChrome {
    val home = activityHomeViewModel()
    val vaults = rememberAdaptiveVaults(repositories, home)
    val syncing = home?.syncAll?.collectAsState()?.value?.busy == true
    return RailChrome(
        toPush = vaults.sumOf { it.ahead },
        syncing = syncing,
        onSyncAll = { home?.syncAll() },
        avatarInitials = avatarInitials,
        onAccount = onAccount,
    )
}

/** Short tablet badge for a vault row: "↑3", "↓2", "✓", "4 edited", "!". */
@Composable
internal fun compactBadge(vault: HomeVault): Pair<String, M3Tone> {
    val s = vault.loaded
    return when {
        vault.status == VaultStatusState.Loading -> stringResource(R.string.adaptive_badge_checking) to M3Tone.Muted
        s == null || !s.safe || vault.failed -> stringResource(R.string.adaptive_badge_attention) to M3Tone.Warn
        s.ahead > 0 && s.behind > 0 -> stringResource(R.string.adaptive_badge_both, s.behind, s.ahead) to M3Tone.Ahead
        s.ahead > 0 -> stringResource(R.string.adaptive_badge_ahead, s.ahead) to M3Tone.Ahead
        s.behind > 0 -> stringResource(R.string.adaptive_badge_behind, s.behind) to M3Tone.Behind
        s.changes > 0 -> stringResource(R.string.adaptive_badge_edited, s.changes) to M3Tone.Ahead
        else -> stringResource(R.string.adaptive_badge_in_sync) to M3Tone.Ok
    }
}

/** "main · ↑3" for a vault with commits to push, otherwise "main · 12m ago". */
@Composable
internal fun compactMeta(vault: HomeVault, now: Long): String {
    val s = vault.loaded ?: return stringResource(
        if (vault.status == VaultStatusState.Loading) R.string.m3home_meta_loading else R.string.m3home_meta_unreadable)
    val branch = s.branch.ifBlank { s.headCommit?.take(7) ?: "—" }
    val tail = when {
        s.ahead > 0 -> stringResource(R.string.adaptive_badge_ahead, s.ahead)
        else -> s.lastSyncAt?.let { com.bontecou.syncmd.ui.components.relativeAgo(it, now) } ?: stringResource(R.string.m3home_meta_not_synced)
    }
    return "$branch · $tail"
}

/** "changes on this tablet" / "changes on this phone" (the Fold boards are phones). */
@Composable
internal fun changesOnDevice(tablet: Boolean, count: Int): String =
    pluralStringResource(if (tablet) R.plurals.adaptive_changes_on_tablet else R.plurals.adaptive_changes_on_phone, count)

/** "Remote has no new commits · fetched 2m ago", from the last fetch (as on the phone Changes tab). */
@Composable
internal fun remoteMeta(overview: VaultOverview?, fallbackFetchedAt: Long? = null): String {
    val behind = overview?.behind
    val remote = when {
        overview != null && overview.upstream == null -> stringResource(R.string.vault_m3_no_upstream)
        behind == null -> stringResource(R.string.vault_m3_remote_not_checked)
        behind > 0 -> pluralStringResource(R.plurals.vault_m3_remote_new, behind, behind)
        else -> stringResource(R.string.vault_m3_remote_no_new)
    }
    val fetched = (overview?.lastFetchAt ?: fallbackFetchedAt)?.let { stringResource(R.string.vault_m3_fetched_ago, vaultAgo(it)) }
    return listOfNotNull(remote, fetched).joinToString(" · ")
}

/** "ideas/" for "ideas/voice-memo-pipeline.md", "/" at the root. */
internal fun folderOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }

internal fun nameOf(path: String): String = path.substringAfterLast('/').ifEmpty { path }
