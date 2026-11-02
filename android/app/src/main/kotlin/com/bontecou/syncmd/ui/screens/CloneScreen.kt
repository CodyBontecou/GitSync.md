package com.bontecou.syncmd.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.AddVaultBusyLine
import com.bontecou.syncmd.ui.components.AddVaultNote
import com.bontecou.syncmd.ui.components.AddVaultProgress
import com.bontecou.syncmd.ui.components.CloneCompletion
import com.bontecou.syncmd.ui.components.FolderLocationField
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.SyncRail
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHero
import com.bontecou.syncmd.ui.theme.TideOutlineButton
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideSyncState
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.CloneViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Reviews a clone's location, then shows progress and checked repository registration.
 *
 * Matches the iOS loading experience from AppState.clone():
 *  • Starts only after the user confirms the clone location.
 *  • Navigates to the repo list on success (calling addRepository first).
 *  • Shows the error with a Retry + Cancel option on failure.
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
) {
    val state by viewModel.cloneState.collectAsState()
    var location by rememberSaveable(repoFullName) { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Tide.colors.paper)) {
        Column(modifier = Modifier.fillMaxSize()) {

            WrappingTopBar(
                title = stringResource(when (state) {
                    is CloneViewModel.CloneState.Idle -> R.string.ui_clone_repository_e8fa94ab
                    is CloneViewModel.CloneState.Cloning -> R.string.ui_cloning_repository_c9d2441e
                    is CloneViewModel.CloneState.Success -> R.string.clone_completed_title
                    is CloneViewModel.CloneState.Error -> R.string.tide_onboarding_clone_failed
                }),
                backLabel = stringResource(R.string.publication_cancel),
                onBack = if (state is CloneViewModel.CloneState.Cloning) null else ({ if (viewModel.reset()) onCancel() }),
                titleModifier = Modifier.testTag("clone-title"))

            Column(
                Modifier.fillMaxSize().weight(1f).navigationBarsPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = Tide.gutter).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val s = state) {
                    is CloneViewModel.CloneState.Idle -> {
                        CloneHeroCard(repoFullName, ClonePhase.READY)
                        FolderLocationField(location, { location = it })
                        TidePrimaryButton(stringResource(R.string.ui_clone_repository_e8fa94ab),
                            onClick = { viewModel.startClone(repoFullName, location) }, modifier = Modifier.testTag("clone-start"))
                    }
                    is CloneViewModel.CloneState.Cloning -> CloneHeroCard(repoFullName, ClonePhase.CLONING)
                    is CloneViewModel.CloneState.Success -> {
                        CloneHeroCard(s.repository, ClonePhase.CLONED)
                        CloneCompletion(s.completed, s.repository.substringAfterLast('/'), settingsViewModel,
                            alias = s.repository, onReviewLfs = onReviewLfs, onRegistered = { onCloneSuccess(s.repository); onSuccess() })
                    }
                    is CloneViewModel.CloneState.Error -> {
                        CloneFailureCard(repoFullName, s.message)
                        FolderLocationField(location, { location = it })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TideOutlineButton(stringResource(R.string.publication_cancel), onClick = { if (viewModel.reset()) onCancel() },
                                height = 60.dp, modifier = Modifier.weight(1f))
                            TidePrimaryButton(stringResource(R.string.ui_retry_942087cc), onClick = { viewModel.startClone(repoFullName, location) },
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private enum class ClonePhase { READY, CLONING, CLONED }

/** Dark state card: repository facts on top, the rail showing where the commits are. */
@Composable
private fun CloneHeroCard(fullName: String, phase: ClonePhase) {
    val c = Tide.colors
    val owner = fullName.substringBeforeLast('/', "")
    val name = fullName.substringAfterLast('/')
    TideHero(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (owner.isNotEmpty()) Text("$owner/", style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.heroMeta)
                Text(name, style = TideType.display(28.sp).copy(textDirection = TextDirection.Ltr), color = c.heroText,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (phase == ClonePhase.CLONED) TidePill(stringResource(R.string.clone_completed_title), c.heroSynced, c.heroSynced.copy(alpha = 0.16f))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tide_onboarding_rail_device), style = TideType.mono(11.sp), color = c.heroMeta)
            SyncRail(if (phase == ClonePhase.CLONED) TideSyncState.InSync else TideSyncState.Behind(3),
                Modifier.weight(1f).padding(horizontal = 10.dp), onHero = true)
            Text("GitHub", style = TideType.mono(11.sp), color = c.heroMeta)
        }
        if (phase == ClonePhase.CLONING) {
            Spacer(Modifier.height(20.dp))
            AddVaultProgress(onHero = true)
            AddVaultBusyLine(stringResource(R.string.ui_cloning_repository_c9d2441e), Modifier.padding(top = 14.dp), onHero = true)
        }
    }
}

@Composable
private fun CloneFailureCard(fullName: String, message: String) {
    val c = Tide.colors
    TideCard {
        Text(fullName, style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
        Spacer(Modifier.height(10.dp))
        AddVaultNote(message, TideBannerTone.DANGER)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun ClonePreviewFrame(content: @Composable () -> Unit) {
    Column(Modifier.background(Tide.colors.paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

@Preview(name = "Clone · ready") @Composable
private fun CloneReadyPreview() = SyncMdTheme(darkTheme = false) { ClonePreviewFrame { CloneHeroCard("octocat/notes", ClonePhase.READY) } }

@Preview(name = "Clone · cloning · dark") @Composable
private fun CloneCloningPreview() = SyncMdTheme(darkTheme = true) { ClonePreviewFrame { CloneHeroCard("octocat/notes", ClonePhase.CLONING) } }

@Preview(name = "Clone · cloned") @Composable
private fun CloneDonePreview() = SyncMdTheme(darkTheme = false) { ClonePreviewFrame { CloneHeroCard("octocat/notes", ClonePhase.CLONED) } }

@Preview(name = "Clone · failed · dark") @Composable
private fun CloneFailedPreview() = SyncMdTheme(darkTheme = true) {
    ClonePreviewFrame { CloneFailureCard("octocat/notes", "Authentication failed. Sign in again and retry.") }
}
