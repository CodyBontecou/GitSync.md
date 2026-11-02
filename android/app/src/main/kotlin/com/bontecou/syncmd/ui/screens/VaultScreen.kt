package com.bontecou.syncmd.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.ui.components.headCommitText
import com.bontecou.syncmd.ui.components.recordedSyncText
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.SyncRail
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideAccessory
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideHero
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideRow
import com.bontecou.syncmd.ui.theme.TideSection
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideSyncState
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.util.FilesAppLauncher
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Vault screen — Tide port of iOS VaultView.
 *
 * State first, git second: a mono owner line and the vault name as a display
 * title, then the dark state hero (eyebrow, plain-language headline, SyncRail,
 * the one right action), then files, changes and the latest commit.
 */
@Composable
fun VaultScreen(
    repositoryPath: String,
    showDebugInfo: Boolean = false,
    onOpenGit: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenDiff: (String) -> Unit = {},
    onNavigateBack: () -> Unit,
    repositoryName: String? = null,
    repositoryOwner: String? = null,
    viewModel: PullViewModel = hiltViewModel(),
) {
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.setRepositoryPath(repositoryPath) }
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val selection by viewModel.selection.collectAsState()
    val requestContext = selection?.takeIf { it.path == repositoryPath }
    val loadedStatus by viewModel.status.collectAsState()
    val status = loadedStatus?.takeIf { requestContext != null && it.repoPath == repositoryPath }
    val isLoading by viewModel.isLoading.collectAsState()
    val loadedError by viewModel.errorMessage.collectAsState()
    val errorMessage = loadedError.takeIf { requestContext != null }
    val loadedAttention by viewModel.attention.collectAsState()
    val attention = loadedAttention.takeIf { requestContext != null }
    // Upstream counts are only shown when a plan for this repository is already loaded.
    val loadedPlan by viewModel.pullPlan.collectAsState()
    val plan = loadedPlan?.takeIf { requestContext != null && it.repoPath == repositoryPath }
    val context = LocalContext.current
    val openInFilesFailed = "Could not open this folder in Files."

    // Catalog identity follows selection; only an unregistered working copy uses its folder name.
    val repoDisplayName = repositoryName?.takeIf { it.isNotBlank() }
        ?: repositoryPath.substringAfterLast("/").ifBlank { "Repository" }

    VaultContent(
        ui = VaultUi(
            hasRepository = repositoryPath.isNotBlank(),
            repositoryPath = repositoryPath,
            displayName = repoDisplayName,
            owner = repositoryOwner.orEmpty(),
            status = status,
            isLoading = isLoading,
            canPull = !isLoading && requestContext != null,
            errorMessage = errorMessage,
            attention = attention,
            ahead = plan?.commitsAhead,
            behind = plan?.commitsBehind,
            showDebugInfo = showDebugInfo,
        ),
        onNavigateBack = onNavigateBack,
        onOpenGit = onOpenGit,
        onOpenFiles = onOpenFiles,
        onOpenDiff = onOpenDiff,
        onPull = { requestContext?.let(viewModel::executePull) },
        onDismissError = { viewModel.clearError(requestContext) },
        onDismissAttention = { viewModel.clearAttention(requestContext) },
        onOpenInFiles = {
            val opened = FilesAppLauncher.openFolder(context, repositoryPath)
            if (!opened) Toast.makeText(context, openInFilesFailed, Toast.LENGTH_SHORT).show()
        },
    )
}

// ─── Stateless content ──────────────────────────────────────────────────────

private data class VaultUi(
    val hasRepository: Boolean,
    val repositoryPath: String,
    val displayName: String,
    val owner: String,
    val status: RepositoryStatus?,
    val isLoading: Boolean,
    val canPull: Boolean,
    val errorMessage: String?,
    val attention: String?,
    /** Upstream commit counts; null when the remote has not been checked. */
    val ahead: Int?,
    val behind: Int?,
    val showDebugInfo: Boolean,
)

/** Which story the hero tells. */
private enum class VaultHeroKind { READING, UNREADABLE, CONFLICT, DIRTY, AHEAD, BEHIND, DIVERGED, EMPTY, IN_SYNC }

private fun VaultUi.heroKind(): VaultHeroKind {
    val s = status ?: return if (isLoading || errorMessage == null) VaultHeroKind.READING else VaultHeroKind.UNREADABLE
    val ahead = ahead ?: 0
    val behind = behind ?: 0
    return when {
        s.allChanges.any { it.isConflicted } -> VaultHeroKind.CONFLICT
        s.totalChanges > 0 -> VaultHeroKind.DIRTY
        ahead > 0 && behind > 0 -> VaultHeroKind.DIVERGED
        behind > 0 -> VaultHeroKind.BEHIND
        ahead > 0 -> VaultHeroKind.AHEAD
        s.headCommit == null -> VaultHeroKind.EMPTY
        else -> VaultHeroKind.IN_SYNC
    }
}

/** Same mapping as iOS: uncommitted changes count as "yours to push". */
private fun VaultUi.railState(): TideSyncState {
    val changes = status?.totalChanges ?: 0
    val ahead = ahead ?: 0
    val behind = behind ?: 0
    val local = if (ahead > 0) ahead else changes
    return TideSyncState.of(local, behind)
}

@Composable
private fun VaultContent(
    ui: VaultUi,
    onNavigateBack: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDiff: (String) -> Unit,
    onPull: () -> Unit,
    onDismissError: () -> Unit,
    onDismissAttention: () -> Unit,
    onOpenInFiles: () -> Unit,
) {
    val c = Tide.colors
    val status = ui.status
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.paper)) {
        TideTopBar(
            onBack = onNavigateBack,
            backLabel = stringResource(R.string.publication_back),
            center = if (status != null) {
                {
                    Text(
                        "⑂ ${status.currentBranch}",
                        style = TideType.mono(12.sp, FontWeight.Medium),
                        color = c.inkMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("vault-branch-caption"),
                    )
                }
            } else null,
            actions = if (ui.hasRepository) {
                {
                    Box {
                        TideCircleButton(stringResource(R.string.tide_vault_more), { menuOpen = true }) {
                            Text("•••", style = TideType.mono(13.sp, FontWeight.SemiBold), color = c.ink)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_open_in_files_e1d250d0), style = TideType.body) },
                                onClick = { menuOpen = false; onOpenInFiles() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tide_vault_git), style = TideType.body) },
                                onClick = { menuOpen = false; onOpenGit() },
                            )
                        }
                    }
                }
            } else null,
        )

        // A plain scrolling column (not lazy): the vault page is a handful of cards, and every
        // card (including the latest-commit overview at the bottom) stays in the semantics tree.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .testTag(if (!ui.isLoading && status != null) "vault-ready" else "vault-loading")
                .verticalScroll(rememberScrollState())
                .padding(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!ui.hasRepository) {
                VaultNoRepository()
            } else {
                VaultTitleBlock(ui.displayName, ui.owner)

                VaultStateHero(
                    ui = ui,
                    onOpenGit = onOpenGit,
                    onPull = onPull,
                )

                if (ui.errorMessage != null) {
                    VaultErrorCard(ui.errorMessage, onDismissError)
                }
                if (ui.attention != null) {
                    TideBanner(
                        title = ui.attention,
                        tone = TideBannerTone.AHEAD,
                        onDismiss = onDismissAttention,
                        dismissLabel = stringResource(R.string.ui_dismiss_48845bff),
                    )
                }

                TideSection(title = stringResource(R.string.tide_vault_files_section)) {
                    TideRow(
                        title = stringResource(R.string.ui_browse_files_c8bb105f),
                        subtitle = stringResource(R.string.ui_read_and_edit_files_in_this_repository_3ecfad78),
                        accessory = TideAccessory.CHEVRON,
                        onClick = onOpenFiles,
                    )
                }

                if (status != null) {
                    val loaded: RepositoryStatus = status
                    VaultChanges(loaded.allChanges, onOpenDiff)
                    VaultLatestCommit(loaded)
                    if (ui.showDebugInfo) VaultDebugInfo(ui.repositoryPath, loaded)
                }
            }
        }
    }
}

// ─── Title ──────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun VaultTitleBlock(name: String, owner: String) {
    val c = Tide.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            // Catalog identity remains addressable as one node, separate from the visible title.
            .testTag("vault-name")
            .semantics { text = AnnotatedString(name); invisibleToUser() },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (owner.isNotBlank()) {
            Text(
                owner,
                style = TideType.meta,
                color = c.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("vault-owner"),
            )
        }
        TideDisplay(name, size = 36.sp, modifier = Modifier.testTag("vault-title"))
    }
}

// ─── State hero ─────────────────────────────────────────────────────────────

@Composable
private fun VaultStateHero(ui: VaultUi, onOpenGit: () -> Unit, onPull: () -> Unit) {
    val c = Tide.colors
    val kind = ui.heroKind()
    val status = ui.status
    val changes = status?.totalChanges ?: 0
    val rail = ui.railState()
    val syncing = ui.isLoading && status != null

    TideHero(Modifier.testTag("vault-hero")) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when {
                kind == VaultHeroKind.READING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.tide_vault_reading), style = TideType.sans(24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em), color = c.heroText)
                        TidePulse(color = c.heroMeta)
                    }
                    return@Column
                }
                kind == VaultHeroKind.UNREADABLE -> {
                    HeroEyebrow(stringResource(R.string.tide_vault_eyebrow_unreadable), c.heroDanger)
                    HeroSentence(stringResource(R.string.tide_vault_headline_unreadable))
                    return@Column
                }
                syncing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.tide_vault_syncing), style = TideType.sans(30.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.04).em), color = c.heroText)
                        Text(stringResource(R.string.ui_loading_repository_15b79262), style = TideType.body, color = c.heroMeta)
                    }
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(3.dp),
                        color = c.heroAhead,
                        trackColor = c.heroLine,
                    )
                }
                kind == VaultHeroKind.CONFLICT -> {
                    val count = status!!.allChanges.count { it.isConflicted }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeroEyebrow(stringResource(R.string.tide_vault_eyebrow_conflict), c.heroDanger)
                        HeroCount(count, c.heroDanger)
                        Text(pluralStringResource(R.plurals.tide_vault_conflicts_to_resolve, count), style = TideType.body, color = c.heroText)
                        Text(stringResource(R.string.tide_vault_conflict_detail), style = TideType.mono(11.sp), color = c.heroMeta, modifier = Modifier.padding(top = 2.dp))
                    }
                    SyncRail(rail, dot = 10.dp, onHero = true)
                }
                kind == VaultHeroKind.DIVERGED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeroEyebrow(stringResource(R.string.tide_vault_eyebrow_both_moved), c.heroDanger)
                        HeroSentence(stringResource(R.string.tide_vault_moved_on))
                    }
                    SyncRail(rail, dot = 10.dp, onHero = true)
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.tide_vault_yours, maxOf(ui.ahead ?: 0, 1)), style = TideType.mono(11.sp), color = c.heroMeta)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.tide_vault_from_remote, ui.behind ?: 0), style = TideType.mono(11.sp), color = c.heroMeta)
                    }
                }
                else -> {
                    val (eyebrow, tint) = when (kind) {
                        VaultHeroKind.DIRTY -> stringResource(R.string.tide_vault_eyebrow_ready_to_commit) to c.heroAhead
                        VaultHeroKind.AHEAD -> stringResource(R.string.tide_vault_eyebrow_ready_to_push) to c.heroAhead
                        VaultHeroKind.BEHIND -> stringResource(R.string.tide_vault_eyebrow_new_on_remote) to c.heroBehind
                        VaultHeroKind.EMPTY -> stringResource(R.string.tide_vault_eyebrow_empty) to c.heroMeta
                        else -> stringResource(R.string.tide_vault_eyebrow_in_sync) to c.heroSynced
                    }
                    HeroEyebrow(eyebrow, tint)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (kind) {
                                VaultHeroKind.DIRTY -> {
                                    HeroCount(changes, c.heroAhead)
                                    Text(pluralStringResource(R.plurals.tide_vault_changes_on_phone, changes), style = TideType.body, color = c.heroText)
                                }
                                else -> {
                                    val (headline, sub) = when (kind) {
                                        VaultHeroKind.AHEAD -> stringResource(R.string.tide_vault_headline_ahead, ui.ahead ?: 0) to stringResource(R.string.tide_vault_sub_ahead)
                                        VaultHeroKind.BEHIND -> stringResource(R.string.tide_vault_headline_behind, ui.behind ?: 0) to stringResource(R.string.tide_vault_sub_behind)
                                        VaultHeroKind.EMPTY -> stringResource(R.string.repository_no_commits) to stringResource(R.string.tide_vault_sub_empty)
                                        else -> stringResource(R.string.tide_vault_headline_in_sync) to stringResource(R.string.tide_vault_sub_in_sync)
                                    }
                                    Text(headline, style = TideType.sans(34.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.04).em, lineHeight = 36.sp), color = tint, maxLines = 2)
                                    Text(sub, style = TideType.body, color = c.heroText)
                                }
                            }
                        }
                        Text(
                            remoteSummary(ui),
                            style = TideType.mono(11.sp).copy(lineHeight = 16.sp),
                            color = c.heroMeta,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    SyncRail(rail, dot = 10.dp, onHero = true)
                }
            }
            VaultHeroActions(ui, kind, onOpenGit, onPull)
        }
    }
}

@Composable
private fun remoteSummary(ui: VaultUi): String = when {
    ui.ahead == null || ui.behind == null -> stringResource(R.string.tide_vault_remote_not_checked)
    (ui.behind ?: 0) > 0 -> stringResource(R.string.tide_vault_remote_new)
    else -> stringResource(R.string.tide_vault_remote_current)
}

@Composable
private fun HeroEyebrow(text: String, tint: Color) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    Text(text.uppercase(locale), style = TideType.mono(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.06.em), color = tint)
}

@Composable
private fun HeroSentence(text: String) {
    Text(text, style = TideType.sans(24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em, lineHeight = 28.sp), color = Tide.colors.heroText)
}

@Composable
private fun HeroCount(count: Int, tint: Color) {
    Text("$count", style = TideType.sans(54.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.05).em, lineHeight = 50.sp), color = tint)
}

/**
 * The one right action for the state, plus a quiet secondary. Pull keeps its
 * `vault-pull` identity and is disabled (not hidden) while an operation runs.
 */
@Composable
private fun VaultHeroActions(ui: VaultUi, kind: VaultHeroKind, onOpenGit: () -> Unit, onPull: () -> Unit) {
    if (kind == VaultHeroKind.READING) return
    val commitPush = stringResource(R.string.ui_commit_push_94e9650a)
    val pull = stringResource(R.string.pull_screen)
    val changes = ui.status?.totalChanges ?: 0
    val busy = ui.isLoading
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (kind) {
            VaultHeroKind.UNREADABLE -> {
                HeroSecondary(commitPush, onOpenGit, enabled = !busy)
            }
            VaultHeroKind.CONFLICT -> {
                HeroPrimary(stringResource(R.string.tide_vault_resolve), "⑂", onOpenGit, enabled = !busy)
                HeroSecondary(commitPush, onOpenGit, enabled = !busy)
            }
            VaultHeroKind.DIRTY, VaultHeroKind.AHEAD -> {
                HeroPrimary(commitPush, "↑", onOpenGit, enabled = !busy, badge = changes.takeIf { it > 0 })
                HeroSecondary(pull, onPull, enabled = ui.canPull, tag = "vault-pull")
            }
            VaultHeroKind.BEHIND, VaultHeroKind.DIVERGED -> {
                HeroPrimary(pull, "↓", onPull, enabled = ui.canPull, tag = "vault-pull")
                HeroSecondary(commitPush, onOpenGit, enabled = !busy)
            }
            else -> {
                HeroPrimary(stringResource(R.string.tide_vault_check_for_changes), "↻", onPull, enabled = ui.canPull, tag = "vault-pull")
                HeroSecondary(commitPush, onOpenGit, enabled = !busy)
            }
        }
    }
}

/** Light pill on the dark hero (heroText fill, heroBg label). */
@Composable
private fun RowScope.HeroPrimary(title: String, glyph: String, onClick: () -> Unit, enabled: Boolean, tag: String? = null, badge: Int? = null) {
    val c = Tide.colors
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(c.heroText.copy(alpha = if (enabled) 1f else 0.45f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(glyph, style = TideType.sans(15.sp, FontWeight.Bold), color = c.heroBg)
        Spacer(Modifier.size(8.dp))
        Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = c.heroBg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) {
            Spacer(Modifier.size(8.dp))
            Box(Modifier.background(c.heroAhead, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("$badge", style = TideType.metaStrong, color = Color.White)
            }
        }
    }
}

/** Outline pill on the dark hero. */
@Composable
private fun HeroSecondary(title: String, onClick: () -> Unit, enabled: Boolean, tag: String? = null) {
    val c = Tide.colors
    Box(
        Modifier
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .border(1.dp, c.heroLine, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(15.sp, FontWeight.Medium), color = c.heroText.copy(alpha = if (enabled) 1f else 0.45f), maxLines = 1)
    }
}

// ─── Error, empty ───────────────────────────────────────────────────────────

@Composable
private fun VaultErrorCard(message: String, onDismiss: () -> Unit) {
    val c = Tide.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.danger.copy(alpha = 0.10f), shape)
            .border(1.dp, c.danger.copy(alpha = 0.25f), shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("!", style = TideType.mono(15.sp, FontWeight.SemiBold), color = c.danger)
        Text(message, style = TideType.sans(14.sp, FontWeight.Medium), color = c.danger, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDismiss)
                .testTag("vault-pull-error-dismiss")
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.ui_dismiss_48845bff), style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.ink)
        }
    }
}

@Composable
private fun VaultNoRepository() {
    val c = Tide.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 120.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(56.dp).background(c.well, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Text("⑂", style = TideType.sans(24.sp, FontWeight.SemiBold), color = c.inkMid)
        }
        TideDisplay(stringResource(R.string.ui_no_repository_555da78f), size = 26.sp)
        Text(stringResource(R.string.ui_go_back_and_select_a_repository_c3eedd39), style = TideType.meta, color = c.inkFaint)
    }
}

// ─── Changes ────────────────────────────────────────────────────────────────

@Composable
private fun VaultChanges(changes: List<GitStatusEntry>, onOpenDiff: (String) -> Unit) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TideSectionLabel(stringResource(R.string.ui_changed_files_cdf024ce))
            if (changes.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text("${changes.size}", style = TideType.mono(11.sp, FontWeight.SemiBold), color = c.ahead)
            }
        }
        if (changes.isEmpty()) {
            TideCard {
                Text(stringResource(R.string.tide_vault_no_changes_title), style = TideType.body, color = c.ink)
                Text(
                    stringResource(R.string.tide_vault_no_changes_body),
                    style = TideType.sans(13.sp),
                    color = c.inkFaint,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        } else {
            TideCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val sorted = changes.sortedBy { it.filePath }
                    sorted.forEachIndexed { index, entry ->
                        ChangedFileRow(entry, onClick = { onOpenDiff(entry.filePath) })
                        if (index < sorted.lastIndex) TideHairline()
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedFileRow(entry: GitStatusEntry, onClick: () -> Unit = {}) {
    val c = Tide.colors
    val label = when {
        entry.isConflicted -> "conflict"
        entry.hasStagedChanges && entry.hasUnstagedChanges -> "staged + unstaged"
        entry.isDeleted -> if (entry.hasStagedChanges) "staged deletion" else "deleted"
        entry.kind == GitFileStatusKind.STAGED -> "staged"
        entry.kind == GitFileStatusKind.UNTRACKED -> "untracked"
        else -> "modified"
    }
    val (glyph, tint) = when {
        entry.isConflicted -> "!" to c.danger
        entry.isDeleted -> "D" to c.danger
        entry.kind == GitFileStatusKind.UNTRACKED -> "A" to c.synced
        else -> "M" to c.ahead
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(26.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = TideType.mono(12.sp, FontWeight.SemiBold), color = tint) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.filePath, style = TideType.mono(13.sp, FontWeight.Medium), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = TideType.mono(11.sp), color = c.inkFaint)
        }
        TideChevron()
    }
}

// ─── Latest commit ──────────────────────────────────────────────────────────

@Composable
private fun VaultLatestCommit(status: RepositoryStatus) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth()) {
        TideSectionLabel(stringResource(R.string.tide_vault_latest_commit), Modifier.padding(start = 4.dp, bottom = 8.dp))
        TideCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.padding(top = 4.dp).size(12.dp).border(2.dp, c.ink, CircleShape),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        headCommitText(status.headCommit),
                        style = TideType.mono(15.sp, FontWeight.SemiBold),
                        color = c.ink,
                        modifier = Modifier.testTag("vault-overview-head"),
                    )
                    Text(
                        "${stringResource(R.string.ui_branch_52656e81)}: ${status.currentBranch}",
                        style = TideType.meta,
                        color = c.inkFaint,
                        modifier = Modifier.testTag("vault-overview-branch"),
                    )
                    Text(
                        "${stringResource(R.string.ui_last_sync_a0d540dd)}: ${recordedSyncText(status.lastSyncAt)}",
                        style = TideType.meta,
                        color = c.inkFaint,
                        modifier = Modifier.testTag("vault-overview-sync"),
                    )
                }
            }
        }
    }
}

// ─── Debug ──────────────────────────────────────────────────────────────────

@Composable
private fun VaultDebugInfo(path: String, status: RepositoryStatus) {
    TideSection(title = stringResource(R.string.ui_debug_info_697b4941)) {
        TideRow(title = stringResource(R.string.ui_path_62fa5a5b), value = path)
        TideHairline()
        TideRow(title = stringResource(R.string.ui_branch_52656e81), value = status.currentBranch)
        TideHairline()
        TideRow(title = stringResource(R.string.modified), value = "${status.modifiedFiles.size}")
        TideHairline()
        TideRow(title = stringResource(R.string.staged), value = "${status.stagedFiles.size}")
        TideHairline()
        TideRow(title = stringResource(R.string.untracked), value = "${status.untrackedFiles.size}")
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewChanges = listOf(
    GitStatusEntry("Daily/2026-11-02.md", GitFileStatusKind.MODIFIED),
    GitStatusEntry("Ideas/tide.md", GitFileStatusKind.UNTRACKED),
    GitStatusEntry("Archive/old.md", GitFileStatusKind.STAGED, isDeleted = true),
)

private fun previewStatus(changes: List<GitStatusEntry> = emptyList(), head: String? = "b32427b9c0") = RepositoryStatus(
    repoPath = "/vaults/notes", currentBranch = "main", isClean = changes.isEmpty(),
    modifiedFiles = changes.filter { it.kind == GitFileStatusKind.MODIFIED },
    untrackedFiles = changes.filter { it.kind == GitFileStatusKind.UNTRACKED },
    stagedFiles = changes.filter { it.kind == GitFileStatusKind.STAGED },
    allChanges = changes, headCommit = head, lastSyncAt = 1_762_000_000_000,
)

private fun previewUi(
    status: RepositoryStatus? = previewStatus(),
    isLoading: Boolean = false,
    error: String? = null,
    ahead: Int? = null,
    behind: Int? = null,
    hasRepository: Boolean = true,
    attention: String? = null,
) = VaultUi(
    hasRepository = hasRepository, repositoryPath = "/vaults/notes", displayName = "Personal notes", owner = "team",
    status = status, isLoading = isLoading, canPull = !isLoading, errorMessage = error, attention = attention,
    ahead = ahead, behind = behind, showDebugInfo = false,
)

@Composable
private fun VaultPreview(ui: VaultUi, dark: Boolean = false) {
    SyncMdTheme(darkTheme = dark) {
        VaultContent(ui, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Vault · in sync", heightDp = 900) @Composable private fun PreviewVaultInSync() = VaultPreview(previewUi(ahead = 0, behind = 0))
@Preview(name = "Vault · in sync dark", heightDp = 900) @Composable private fun PreviewVaultInSyncDark() = VaultPreview(previewUi(ahead = 0, behind = 0), dark = true)
@Preview(name = "Vault · dirty", heightDp = 1000) @Composable private fun PreviewVaultDirty() = VaultPreview(previewUi(previewStatus(previewChanges)))
@Preview(name = "Vault · dirty dark", heightDp = 1000) @Composable private fun PreviewVaultDirtyDark() = VaultPreview(previewUi(previewStatus(previewChanges)), dark = true)
@Preview(name = "Vault · ahead", heightDp = 900) @Composable private fun PreviewVaultAhead() = VaultPreview(previewUi(ahead = 2, behind = 0))
@Preview(name = "Vault · behind", heightDp = 900) @Composable private fun PreviewVaultBehind() = VaultPreview(previewUi(ahead = 0, behind = 3))
@Preview(name = "Vault · diverged", heightDp = 900) @Composable private fun PreviewVaultDiverged() = VaultPreview(previewUi(ahead = 1, behind = 1))
@Preview(name = "Vault · diverged dark", heightDp = 900) @Composable private fun PreviewVaultDivergedDark() = VaultPreview(previewUi(ahead = 1, behind = 1), dark = true)
@Preview(name = "Vault · conflict", heightDp = 900) @Composable private fun PreviewVaultConflict() = VaultPreview(previewUi(previewStatus(listOf(GitStatusEntry("Daily/2026-11-02.md", GitFileStatusKind.MODIFIED, isConflicted = true)))))
@Preview(name = "Vault · syncing", heightDp = 900) @Composable private fun PreviewVaultSyncing() = VaultPreview(previewUi(isLoading = true))
@Preview(name = "Vault · reading", heightDp = 700) @Composable private fun PreviewVaultReading() = VaultPreview(previewUi(status = null, isLoading = true))
@Preview(name = "Vault · unreadable", heightDp = 700) @Composable private fun PreviewVaultUnreadable() = VaultPreview(previewUi(status = null, error = "Repository not found"))
@Preview(name = "Vault · pull error", heightDp = 1000) @Composable private fun PreviewVaultError() = VaultPreview(previewUi(error = "Owned pull failure", attention = "Pulled with conflicts in 1 file"))
@Preview(name = "Vault · empty repo", heightDp = 900) @Composable private fun PreviewVaultEmptyRepo() = VaultPreview(previewUi(previewStatus(head = null)))
@Preview(name = "Vault · no repository", heightDp = 700) @Composable private fun PreviewVaultNoRepo() = VaultPreview(previewUi(status = null, hasRepository = false), dark = true)
