package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.github.GitHubRepo
import com.bontecou.syncmd.services.github.RepoNoteSignals
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.components.AddVaultBusy
import com.bontecou.syncmd.ui.components.AddVaultCloneStatusBar
import com.bontecou.syncmd.ui.components.addVaultAge
import com.bontecou.syncmd.ui.components.addVaultFriendlyPath
import com.bontecou.syncmd.ui.components.addVaultSize
import com.bontecou.syncmd.ui.components.parseGitHubTime
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3OwnerChip
import com.bontecou.syncmd.ui.theme.M3SearchField
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TonalButton
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3GroupedItem
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.CloneViewModel
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.RepoOwnerFilter
import com.bontecou.syncmd.ui.viewmodels.RepoPickerViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Board 13 · Pick a repo: @user, owner chips (Yours · Orgs · Starred), repos grouped into
 * "Looks like notes" and "Everything else", each with "Added" or a Clone button. Clone starts here
 * (after the free check on locked installs) and shows its progress in the bottom bar; tapping a row
 * opens the full Clone screen to pick another folder.
 */
@Composable
fun RepoPickerScreen(
    settingsViewModel: SettingsViewModel,
    onRepoSelected: (GitHubRepo) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: GitHubViewModel = hiltViewModel(),
    onManageAccounts: () -> Unit = {},
    onCloned: () -> Unit = onNavigateBack,
    onReviewLfs: ((String) -> Unit)? = null,
    picker: RepoPickerViewModel = hiltViewModel(),
    clone: CloneViewModel = hiltViewModel(),
) {
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val user by viewModel.user.collectAsState()
    val repos by viewModel.repos.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error = viewModel.error.collectAsState().value?.asString()
    val filter by picker.filter.collectAsState()
    val starred by picker.starred.collectAsState()
    val starredLoading by picker.starredLoading.collectAsState()
    val starredError by picker.starredError.collectAsState()
    val signals by picker.signals.collectAsState()
    val saved by settingsViewModel.allRepositories.collectAsState()
    val cloneState by clone.cloneState.collectAsState()
    val progress by clone.progress.collectAsState()
    val info by clone.info.collectAsState()
    val destination by clone.destination.collectAsState()
    var cloning by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    if (!isLoggedIn) {
        LoginScreen(viewModel = viewModel, settingsViewModel = settingsViewModel, onContinueAfterLogin = onNavigateBack)
        return
    }

    val login = user?.login
    val source = when (filter) {
        RepoOwnerFilter.YOURS -> repos.filter { login == null || it.ownerLogin.equals(login, ignoreCase = true) }
        RepoOwnerFilter.ORGS -> repos.filter { login != null && !it.ownerLogin.equals(login, ignoreCase = true) }
        RepoOwnerFilter.STARRED -> starred.orEmpty()
    }
    val visible = source.filter { searchQuery.isBlank() || it.name.contains(searchQuery, true) || it.fullName.contains(searchQuery, true) ||
        it.description?.contains(searchQuery, true) == true }
    LaunchedEffect(source) { if (source.isNotEmpty()) picker.detect(source) }

    // Quick clone: the free check first on locked installs, then progress in the bottom bar.
    val gate = com.bontecou.syncmd.ui.util.rememberUnlockGate()
    val unlocked by gate.isUnlocked.collectAsState()
    val price by gate.price.collectAsState()
    var checkTarget by remember { mutableStateOf<com.bontecou.syncmd.billing.VaultTarget?>(null) }
    fun begin(repo: GitHubRepo) {
        if (cloneState !is CloneViewModel.CloneState.Idle) return
        cloning = repo.fullName
        clone.loadInfo(repo.fullName, CloneViewModel.RepoInfo(repo.defaultBranch, repo.isPrivate, repo.sizeKilobytes?.times(1024)))
        if (!gate.paywallEnabled || unlocked) clone.startClone(repo.fullName) else clone.vaultTarget(repo.fullName)?.let { checkTarget = it }
    }
    checkTarget?.let { target ->
        VaultCheckScreen(target, locked = !unlocked, price = price,
            onClone = { detail -> gate.require(target.name, detail) { checkTarget = null; cloning?.let { clone.startClone(it) } } },
            onDismiss = { checkTarget = null; if (clone.cloneState.value is CloneViewModel.CloneState.Idle) cloning = null },
            clonesTo = runCatching { addVaultFriendlyPath(context, RepositoryLocations(context).summary().path + "/" + target.name, " › ") }.getOrNull())
    }

    Box(Modifier.fillMaxSize()) {
        RepoPickerContent(
            login = login,
            groups = repoPickerGroups(visible, signals),
            filter = filter,
            searchQuery = searchQuery,
            isLoading = if (filter == RepoOwnerFilter.STARRED) starredLoading && starred == null else isLoading && repos.isEmpty(),
            error = if (filter == RepoOwnerFilter.STARRED && starredError) stringResource(R.string.add_picker_starred_failed) else error,
            added = { repo -> saved.any { savedRepo -> savedRepo.alias.equals(repo.fullName, true) ||
                savedRepo.remoteUrl?.trimEnd('/')?.removeSuffix(".git")?.equals("https://github.com/${repo.fullName}", true) == true } },
            signals = signals,
            busy = cloneState !is CloneViewModel.CloneState.Idle,
            onFilter = picker::select,
            onSearch = viewModel::onSearchQueryChanged,
            onRefresh = {
                if (filter == RepoOwnerFilter.STARRED) picker.loadStarred(force = true) else viewModel.loadUserAndRepos(forceRefresh = true)
                picker.detect(source, force = true)
            },
            onBack = onNavigateBack,
            onManageAccounts = onManageAccounts,
            onOpen = onRepoSelected,
            onClone = ::begin,
            bottomPadding = if (cloneState is CloneViewModel.CloneState.Cloning) 100.dp else 24.dp,
        )
        if (cloneState is CloneViewModel.CloneState.Cloning) {
            BackHandler { /* The clone keeps going; it finishes into this screen. */ }
            AddVaultCloneStatusBar((cloning ?: "").substringAfterLast('/'), progress, info?.sizeBytes,
                destination?.let { addVaultFriendlyPath(context, it, " › ") },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp))
        }
        val repo = cloning
        if (repo != null && (cloneState is CloneViewModel.CloneState.Success || cloneState is CloneViewModel.CloneState.Error)) {
            // Finishing up, Git LFS review and failures use the Cloning board over the picker.
            CloneScreen(repo, settingsViewModel, onCloneSuccess = {}, onSuccess = { cloning = null; onCloned() },
                onCancel = { cloning = null }, viewModel = clone, onReviewLfs = onReviewLfs)
        }
    }
}

/** A titled group of the picker list. */
internal data class RepoPickerGroup(val notes: Boolean, val repos: List<GitHubRepo>)

/** "Looks like notes" first (detected `.obsidian/` or a mostly-Markdown tree), then everything else; GitHub's order within each. */
internal fun repoPickerGroups(repos: List<GitHubRepo>, signals: Map<String, RepoNoteSignals>): List<RepoPickerGroup> {
    val (notes, other) = repos.partition { signals[it.fullName]?.looksLikeNotes == true }
    return listOfNotNull(RepoPickerGroup(true, notes).takeIf { notes.isNotEmpty() }, RepoPickerGroup(false, other).takeIf { other.isNotEmpty() })
}

@Composable
private fun RepoPickerContent(
    login: String?,
    groups: List<RepoPickerGroup>,
    filter: RepoOwnerFilter,
    searchQuery: String,
    isLoading: Boolean,
    error: String?,
    added: (GitHubRepo) -> Boolean,
    signals: Map<String, RepoNoteSignals>,
    busy: Boolean,
    onFilter: (RepoOwnerFilter) -> Unit,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onManageAccounts: () -> Unit,
    onOpen: (GitHubRepo) -> Unit,
    onClone: (GitHubRepo) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("repo-picker")) {
        M3TopBar(stringResource(R.string.add_picker_title), subtitle = login?.let { "@$it" }, onBack = onBack) {
            M3IconButton(R.drawable.ic_m3_person, stringResource(R.string.add_picker_accounts), onManageAccounts)
            M3IconButton(R.drawable.ic_m3_refresh, stringResource(R.string.add_picker_refresh), onRefresh)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
            item(key = "search") {
                M3SearchField(searchQuery, onSearch, stringResource(R.string.add_picker_search),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("repo-search"),
                    trailing = if (searchQuery.isNotEmpty()) ({ M3IconButton(R.drawable.ic_m3_close, stringResource(R.string.tide_onboarding_picker_clear), { onSearch("") }, size = 40.dp, iconSize = 20.dp) }) else null)
            }
            item(key = "owners") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepoOwnerFilter.entries.forEach { owner ->
                        M3OwnerChip(stringResource(when (owner) {
                            RepoOwnerFilter.YOURS -> R.string.add_owner_yours
                            RepoOwnerFilter.ORGS -> R.string.add_owner_orgs
                            RepoOwnerFilter.STARRED -> R.string.add_owner_starred
                        }), selected = filter == owner, onClick = { onFilter(owner) }, modifier = Modifier.testTag("repo-owner-${owner.name.lowercase()}"))
                    }
                }
            }
            if (error != null) item(key = "error") { M3Note(error, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), tone = M3NoteTone.Warn) }
            when {
                isLoading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { AddVaultBusy(stringResource(R.string.ui_loading_repos_2f4f4a93)) }
                }
                groups.isEmpty() -> item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(if (searchQuery.isNotBlank()) R.string.tide_onboarding_picker_no_matches else R.string.add_picker_empty_owner),
                            style = M3Type.bodyStrong, color = c.on)
                        if (searchQuery.isNotBlank()) Text(stringResource(R.string.account_no_matching_repositories, searchQuery), style = M3Type.bodyMedium, color = c.onVar)
                        else M3Button(stringResource(R.string.add_picker_refresh), onRefresh, style = M3ButtonStyle.Text)
                    }
                }
                else -> groups.forEach { group ->
                    item(key = "group-${group.notes}") {
                        M3SectionLabel(stringResource(when {
                            group.notes -> R.string.add_group_notes
                            searchQuery.isNotBlank() -> R.string.add_group_other_matching
                            else -> R.string.add_group_other
                        }), count = if (group.notes) group.repos.size.toString() else null,
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp))
                    }
                    itemsIndexed(group.repos, key = { _, repo -> "repo-${group.notes}-${repo.id}" }) { index, repo ->
                        val isAdded = added(repo)
                        RepoPickerRow(repo, signals[repo.fullName], isAdded, busy,
                            Modifier.padding(horizontal = 16.dp).m3GroupedItem(index, group.repos.size, c.cLow), divider = index > 0,
                            onOpen = { onOpen(repo) }, onClone = { onClone(repo) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoPickerRow(repo: GitHubRepo, signals: RepoNoteSignals?, added: Boolean, busy: Boolean, modifier: Modifier, divider: Boolean,
    onOpen: () -> Unit, onClone: () -> Unit) {
    val c = M3.colors
    val size = repo.sizeKilobytes?.takeIf { !added && it > 0 }?.let { addVaultSize(it * 1024) }
    val meta = listOfNotNull(
        stringResource(if (repo.isPrivate) R.string.add_meta_private else R.string.add_meta_public),
        stringResource(R.string.add_meta_obsidian).takeIf { signals?.obsidian == true },
        size?.let { if (signals?.lfs == true) stringResource(R.string.add_meta_lfs, it) else it },
        addVaultAge(parseGitHubTime(repo.pushedAt ?: repo.updatedAt)),
    ).joinToString(" · ")
    M3ListRow(repo.name, modifier.testTag("repo-${repo.fullName}"), meta = meta, divider = divider, onClick = if (added) null else onOpen,
        enabled = true, contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), titleMaxLines = 1,
        trailing = {
            if (added) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                M3Icon(R.drawable.ic_m3_check, tint = c.ok, size = 18.dp)
                Text(stringResource(R.string.add_added), style = M3Type.badge.copy(fontSize = M3Type.bodySmall.fontSize), color = c.ok)
            } else M3TonalButton(stringResource(R.string.add_clone), onClone, Modifier.testTag("repo-clone-${repo.fullName}"), enabled = !busy)
        })
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewRepos = listOf(
    GitHubRepo(1, "VoxVault", "CodyBontecou/VoxVault", null, true, "", "main", "2026-11-06T08:00:00Z", "CodyBontecou", null, 0, 2_000),
    GitHubRepo(2, "reading-vault", "CodyBontecou/reading-vault", null, true, "", "main", "2026-11-03T10:00:00Z", "CodyBontecou", null, 0, 4_000),
    GitHubRepo(3, "vault-templates", "CodyBontecou/vault-templates", null, false, "", "main", "2026-09-01T10:00:00Z", "CodyBontecou", null, 0, 18),
)

@Preview(name = "Picker", widthDp = 412, heightDp = 915) @Composable
private fun PickerPreview() = SyncMdTheme(darkTheme = false) {
    val signals = mapOf("CodyBontecou/VoxVault" to RepoNoteSignals(true, 4, 1, false), "CodyBontecou/reading-vault" to RepoNoteSignals(true, 9, 2, false))
    RepoPickerContent("CodyBontecou", repoPickerGroups(previewRepos, signals), RepoOwnerFilter.YOURS, "", false, null, { it.id == 1L }, signals, false,
        {}, {}, {}, {}, {}, {}, {}, 24.dp)
}

@Preview(name = "Picker · dark", widthDp = 412, heightDp = 915) @Composable
private fun PickerDarkPreview() = SyncMdTheme(darkTheme = true) {
    RepoPickerContent("CodyBontecou", emptyList(), RepoOwnerFilter.STARRED, "", true, null, { false }, emptyMap(), false, {}, {}, {}, {}, {}, {}, {}, 24.dp)
}
