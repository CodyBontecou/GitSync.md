package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.TransferProgressUpdate
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.components.AddVaultCloneHero
import com.bontecou.syncmd.ui.components.AddVaultInlineIssue
import com.bontecou.syncmd.ui.components.AddVaultLocationCard
import com.bontecou.syncmd.ui.components.CloneCompletion
import com.bontecou.syncmd.ui.components.addVaultFriendlyPath
import com.bontecou.syncmd.ui.components.addVaultSize
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.CloneViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Board Cloning: review the clone location, then progress, finishing up, the Git LFS review and
 * failure with Retry. Starts only after the user taps Clone; locked installs take the free vault check first.
 */
@Composable
fun CloneScreen(
    repoFullName: String,
    settingsViewModel: SettingsViewModel,
    onCloneSuccess: (String) -> Unit,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    viewModel: CloneViewModel = hiltViewModel(),
    onReviewLfs: ((String) -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
) {
    val state by viewModel.cloneState.collectAsState()
    val info by viewModel.info.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val destination by viewModel.destination.collectAsState()
    var location by rememberSaveable(repoFullName) { mutableStateOf<String?>(null) }
    LaunchedEffect(repoFullName) { viewModel.loadInfo(repoFullName) }
    // Locked installs check the repository for free first, then ask for the unlock (as on iOS).
    val gate = com.bontecou.syncmd.ui.util.rememberUnlockGate()
    val unlocked by gate.isUnlocked.collectAsState()
    val price by gate.price.collectAsState()
    var checkTarget by remember { mutableStateOf<com.bontecou.syncmd.billing.VaultTarget?>(null) }
    val context = LocalContext.current
    val start: () -> Unit = {
        if (!gate.paywallEnabled || unlocked) viewModel.startClone(repoFullName, location)
        else viewModel.vaultTarget(repoFullName)?.let { checkTarget = it }
    }
    checkTarget?.let { target ->
        VaultCheckScreen(target, locked = !unlocked, price = price,
            onClone = { detail -> gate.require(target.name, detail) { checkTarget = null; viewModel.startClone(repoFullName, location) } }, onDismiss = { checkTarget = null },
            clonesTo = runCatching { addVaultFriendlyPath(context, RepositoryLocations(context).summary(location).path + "/" + target.name, " › ") }.getOrNull())
    }
    val cloning = state is CloneViewModel.CloneState.Cloning
    BackHandler(cloning) { /* The clone keeps its publication; leaving mid-transfer would orphan it. */ }
    CloneContent(
        repoFullName = repoFullName, state = state, info = info, progress = progress, stats = stats,
        destination = destination?.let { addVaultFriendlyPath(context, it, " › ") },
        location = location, onLocation = { location = it },
        onStart = start, onCancel = { if (viewModel.reset()) onCancel() }, onSignIn = onSignIn,
        completion = { success ->
            CloneCompletion(success.completed, success.repository.substringAfterLast('/'), settingsViewModel,
                alias = success.repository, onReviewLfs = onReviewLfs, onRegistered = { onCloneSuccess(success.repository); onSuccess() })
        },
    )
}

@Composable
private fun CloneContent(
    repoFullName: String,
    state: CloneViewModel.CloneState,
    info: CloneViewModel.RepoInfo?,
    progress: TransferProgressUpdate?,
    stats: CloneViewModel.ClonedStats?,
    destination: String?,
    location: String?,
    onLocation: (String?) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: (() -> Unit)?,
    completion: @Composable (CloneViewModel.CloneState.Success) -> Unit,
) {
    val c = M3.colors
    val cloning = state is CloneViewModel.CloneState.Cloning
    val cloned = state is CloneViewModel.CloneState.Success
    val failed = state as? CloneViewModel.CloneState.Error
    val name = repoFullName.substringAfterLast('/')
    Column(Modifier.fillMaxSize().background(c.surface).testTag("clone-screen")) {
        M3TopBar(stringResource(when (state) {
            is CloneViewModel.CloneState.Idle -> R.string.add_clone_title_ready
            is CloneViewModel.CloneState.Cloning -> R.string.add_clone_title_cloning
            is CloneViewModel.CloneState.Success -> R.string.add_clone_title_done
            is CloneViewModel.CloneState.Error -> R.string.add_clone_title_failed
        }), Modifier.testTag("clone-title"), onBack = if (cloning) null else onCancel, backIcon = R.drawable.ic_m3_close,
            backLabel = stringResource(R.string.add_clone_cancel))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            if (failed == null) {
                val meta = if (cloned) stats?.let { s ->
                    listOfNotNull(s.branch ?: info?.branch, pluralStringResource(R.plurals.add_clone_files, s.files, s.files), addVaultSize(s.bytes)).joinToString(" · ")
                } else info?.let { i ->
                    listOfNotNull(i.branch, i.isPrivate?.let { stringResource(if (it) R.string.add_meta_private else R.string.add_meta_public) },
                        i.sizeBytes?.let(::addVaultSize)).joinToString(" · ").ifEmpty { null }
                }
                AddVaultCloneHero(repoFullName, meta, cloned = cloned, cloning = cloning, progress = progress, totalBytes = info?.sizeBytes,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
            if (cloning && destination != null) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Icon(R.drawable.ic_m3_folder, tint = c.onVar, size = 20.dp)
                Text(stringResource(R.string.add_clone_to, destination), style = M3Type.meta, color = c.onVar)
            }
            if (failed != null) M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("clone-failure")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(repoFullName, style = M3Type.meta, color = c.onVar)
                    AddVaultInlineIssue(failed.message, Modifier.testTag("clone-error"))
                    if (onSignIn != null && isAuthFailure(failed.message)) M3Button(stringResource(R.string.add_signin_github), onSignIn,
                        style = M3ButtonStyle.Text, size = M3ButtonSize.Medium)
                }
            }
            if (state is CloneViewModel.CloneState.Idle || failed != null) {
                AddVaultLocationCard(location, onLocation, name, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            }
            if (state is CloneViewModel.CloneState.Idle) {
                Text(stringResource(R.string.add_clone_nothing_yet), style = M3Type.caption, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
                M3Button(stringResource(R.string.add_clone_start), onStart, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("clone-start"),
                    style = M3ButtonStyle.Filled, size = M3ButtonSize.Hero, icon = R.drawable.ic_m3_arrow_downward)
            }
            if (failed != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                M3Button(stringResource(R.string.add_clone_cancel), onCancel, Modifier.weight(1f), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Hero)
                M3Button(stringResource(R.string.add_clone_retry), onStart, Modifier.weight(1f).testTag("clone-retry"), style = M3ButtonStyle.Filled, size = M3ButtonSize.Hero,
                    icon = R.drawable.ic_m3_sync)
            }
            if (state is CloneViewModel.CloneState.Success) completion(state)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun isAuthFailure(message: String): Boolean = message.contains("auth", ignoreCase = true) || message.contains("sign in", ignoreCase = true)

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun ClonePreview(state: CloneViewModel.CloneState, progress: TransferProgressUpdate? = null) {
    CloneContent("CodyBontecou/reading-vault", state, CloneViewModel.RepoInfo("main", true, 4_000_000), progress,
        CloneViewModel.ClonedStats("main", 214, 4_000_000), "Documents › GitSync.md › reading-vault", null, {}, {}, {}, null) {}
}

@Preview(name = "Clone · ready", widthDp = 412, heightDp = 915) @Composable
private fun CloneReadyPreview() = SyncMdTheme(darkTheme = false) { Box { ClonePreview(CloneViewModel.CloneState.Idle) } }

@Preview(name = "Clone · cloning · dark", widthDp = 412, heightDp = 915) @Composable
private fun CloneCloningPreview() = SyncMdTheme(darkTheme = true) {
    ClonePreview(CloneViewModel.CloneState.Cloning, TransferProgressUpdate("Receiving objects", 45, 100, 1_800_000))
}

@Preview(name = "Clone · failed", widthDp = 412, heightDp = 915) @Composable
private fun CloneFailedPreview() = SyncMdTheme(darkTheme = false) { ClonePreview(CloneViewModel.CloneState.Error("Authentication failed. Sign in again and retry.")) }
