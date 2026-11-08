package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.services.git.GitWorkspaceDetails
import com.bontecou.syncmd.ui.components.AbortMergeDialog
import com.bontecou.syncmd.ui.components.ConflictExcerpts
import com.bontecou.syncmd.ui.components.ConflictSideCard
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.gitAgo
import com.bontecou.syncmd.ui.components.gitIsGitHub
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3StepProgress
import com.bontecou.syncmd.ui.theme.M3TopBarTextAction
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolve conflict (Material 3 board 09): a full screen, one conflicted file at a time. Abort in the
 * top bar, a step progress bar, "Conflict i of n · path", a headline naming the edited section, and
 * a radio card per side ("This phone · edited X ago" / "GitHub · from <committer> · Y ago"). Keep both
 * joins the versions between clear markers; Edit by hand opens the editor on the marked-up file. The
 * footer uses the selected side. After the last file, an active merge is committed; standalone
 * index conflicts return with the resolution staged and HEAD unchanged.
 */
@Composable
fun ConflictScreen(
    path: String,
    vaultName: String,
    onBack: () -> Unit,
    onNavigate: (GitDestination) -> Unit,
    conflictVM: ConflictViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
    initialFilePath: String? = null,
) {
    LaunchedEffect(path) { conflictVM.setRepositoryPath(path); workspaceVM.load(path) }
    com.bontecou.syncmd.ui.util.RepositoryResumeEffect(path) { conflictVM.loadConflicts() }
    val conflicts by conflictVM.conflicts.collectAsState()
    val resolved by conflictVM.resolvedConflicts.collectAsState()
    val mergeState by conflictVM.mergeState.collectAsState()
    val busy by conflictVM.isLoading.collectAsState()
    val ready by conflictVM.actionsReady.collectAsState()
    val selection by conflictVM.selection.collectAsState()
    val error = conflictVM.errorMessage.collectAsState().value?.asString()
    val context = selection?.takeIf { it.path == path }
    val workspace by workspaceVM.state.collectAsState()
    val androidContext = LocalContext.current

    val conflict: Conflict? = conflicts.firstOrNull { initialFilePath != null && initialFilePath in it.allPaths } ?: conflicts.firstOrNull()
    var total by rememberSaveable(path) { mutableIntStateOf(0) }
    LaunchedEffect(conflicts.size, resolved.size) { total = maxOf(total, conflicts.size + resolved.size) }
    var side by rememberSaveable(conflict?.filePath) { mutableIntStateOf(0) }
    var handEdited by rememberSaveable(path) { mutableStateOf(setOf<String>()) }
    var abort by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var aborting by remember { mutableStateOf(false) }
    LaunchedEffect(conflict?.filePath) { conflict?.let { workspaceVM.loadProvenance(path, it.filePath) } }
    val provenance = (conflict?.filePath ?: mergeState?.unmergedFiles?.firstOrNull())?.let { workspace.provenance[it] }
        ?: workspace.provenance.values.firstOrNull()
    LaunchedEffect(Unit) { if (conflict == null) workspaceVM.loadProvenance(path, "") }

    val source = provenance?.source ?: mergeState?.sourceBranch?.takeIf { it.isNotBlank() }
    val remoteSide = provenance?.sourceIsRemote != false
    val unnamedIncoming = mergeState?.isMergeInProgress == false && source == null && provenance?.sourceIsRemote != true
    val theirsName = if (unnamedIncoming) stringResource(R.string.tide_merge_theirs)
        else if (remoteSide) (if (gitIsGitHub(workspace.summary?.remoteUrl) || workspace.summary == null) stringResource(R.string.git_m3_github)
        else workspace.summary?.remoteUrl?.let { com.bontecou.syncmd.ui.components.gitRemoteDisplay(it)?.substringBefore('/') } ?: stringResource(R.string.git_m3_github))
        else source ?: stringResource(R.string.git_m3_github)
    val oursName = stringResource(R.string.git_m3_this_phone)

    // Read the file on disk to notice a hand edit (markers removed in the editor).
    val disk by produceState<String?>(null, conflict, handEdited) {
        value = conflict?.takeIf { it.filePath in handEdited }?.let { c ->
            runCatching { withContext(Dispatchers.IO) { GitWorkspaceDetails.workTreeText(path, c.filePath) } }.getOrNull()
        }
    }
    // One read of the delegated state, so the check and the accept use the same text.
    val diskText = disk
    val editedClean = conflict != null && conflict.filePath in handEdited && diskText != null && diskText.lineSequence().none { it.startsWith("<<<<<<<") || it.startsWith(">>>>>>>") }

    // Complete only an actual merge. A stash conflict resolves without creating a commit.
    LaunchedEffect(conflicts.isEmpty(), ready, mergeState?.isMergeInProgress, total) {
        if (context == null || !ready || conflicts.isNotEmpty()) return@LaunchedEffect
        if (mergeState?.isMergeInProgress == true && !finishing && !aborting) {
            finishing = true
            val message = provenance?.mergeMessage?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: androidContext.getString(R.string.git_m3_merge_message, source ?: theirsName)
            conflictVM.completeMerge(context, message)
        } else if (mergeState?.isMergeInProgress != true) onBack()
    }
    LaunchedEffect(busy, error) { if (!busy && error != null) { finishing = false; aborting = false } }
    BackHandler(busy) {}

    GitPage(
        title = stringResource(R.string.git_m3_resolve_conflict),
        subtitle = source?.let { stringResource(R.string.git_m3_merging, vaultName, it) } ?: vaultName,
        onBack = { if (!busy) onBack() },
        modifier = Modifier.testTag("conflict-screen"),
        scrollTag = "conflict-scroll",
        actions = { M3TopBarTextAction(stringResource(R.string.git_m3_abort), { abort = true }, Modifier.testTag("conflict-abort"), destructive = true,
            enabled = context != null && ready && !busy && mergeState?.isMergeInProgress == true) },
        bottomBar = conflict?.let { current ->
            {
                M3BottomActionBar {
                    val label = when {
                        editedClean -> stringResource(R.string.git_m3_use_edited)
                        side == 0 -> stringResource(R.string.git_m3_use_phone)
                        unnamedIncoming -> stringResource(R.string.adaptive_keep_this_version)
                        remoteSide -> stringResource(R.string.git_m3_use_remote, theirsName)
                        else -> stringResource(R.string.git_m3_use_branch, theirsName)
                    }
                    M3Button(label, {
                        val expected = context ?: return@M3Button
                        when {
                            editedClean && diskText != null -> conflictVM.acceptContent(expected, current, diskText) { handEdited = handEdited - current.filePath }
                            side == 0 -> conflictVM.acceptOurs(expected, current)
                            else -> conflictVM.acceptTheirs(expected, current)
                        }
                    }, Modifier.fillMaxWidth().testTag("conflict-use"), size = M3ButtonSize.XLarge, enabled = context != null && ready && !busy, loading = busy)
                }
            }
        },
        bottomSpace = 24.dp,
    ) {
        val c = M3.colors
        val done = (total - conflicts.size).coerceAtLeast(0)
        if (total > 0) M3StepProgress(total, (done + if (conflict != null) 1 else 0).coerceAtMost(total), Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
        if (conflict == null) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(stringResource(if (mergeState?.isMergeInProgress == true) R.string.git_m3_finishing_merge else R.string.git_m3_no_conflicts),
                    style = M3Type.headline, color = c.on)
                if (busy || finishing) M3IndeterminateProgressBar(Modifier.padding(top = 16.dp))
            }
            return@GitPage
        }
        Text(stringResource(R.string.git_m3_conflict_progress, (done + 1).coerceAtMost(total.coerceAtLeast(1)), total.coerceAtLeast(1), conflict.filePath),
            Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).testTag("git-conflict-${conflict.filePath}"), style = M3Type.meta, color = c.tertiary)
        val textual = !conflict.isBinaryOrTooLarge && conflict.hasCurrentVersion && conflict.hasIncomingVersion
        val heading = if (textual) ConflictExcerpts.heading(conflict.currentContent, conflict.incomingContent) else null
        Text(heading?.let { stringResource(R.string.git_m3_both_edited_section, it) } ?: stringResource(when {
            !conflict.hasCurrentVersion -> R.string.git_m3_deleted_here_edited_there
            !conflict.hasIncomingVersion -> R.string.git_m3_edited_here_deleted_there
            conflict.isRenameRename -> R.string.conflict_renamed_differently
            else -> R.string.git_m3_both_edited_file
        }), Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp), style = M3Type.headline.copy(letterSpacing = M3Type.title.letterSpacing), color = c.on)
        Text(stringResource(R.string.git_m3_conflict_body), Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp), style = M3Type.bodyMedium, color = c.onVar)

        if (conflict.filePath in handEdited) M3Note(stringResource(if (editedClean) R.string.git_m3_hand_edited_ready else R.string.git_m3_hand_edited_markers),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("conflict-hand-edit-note"), tone = if (editedClean) M3NoteTone.Ok else M3NoteTone.Ahead)

        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val oursWhen = provenance?.oursTime?.let { stringResource(R.string.git_m3_edited_ago, gitAgo(androidContext, it)) }.orEmpty()
            val theirsWhen = listOfNotNull(provenance?.theirsName?.let { stringResource(R.string.git_m3_from_who, it) },
                provenance?.theirsTime?.let { gitAgo(androidContext, it) }).joinToString(" · ")
            val binary = stringResource(R.string.git_m3_binary_side)
            ConflictSideCard(oursName, oursWhen, side == 0, ours = true,
                excerpt = if (textual) ConflictExcerpts.excerpt(conflict.currentContent, conflict.incomingContent) else null,
                note = when { !conflict.hasCurrentVersion -> stringResource(R.string.git_m3_deleted_on_phone); !textual -> binary; else -> null },
                onSelect = { side = 0 }, modifier = Modifier.testTag("conflict-side-ours"))
            ConflictSideCard(theirsName, theirsWhen, side == 1, ours = false,
                excerpt = if (textual) ConflictExcerpts.excerpt(conflict.incomingContent, conflict.currentContent) else null,
                note = when { !conflict.hasIncomingVersion -> stringResource(R.string.git_m3_deleted_on_remote, theirsName); !textual -> binary; else -> null },
                onSelect = { side = 1 }, modifier = Modifier.testTag("conflict-side-theirs"))
        }
        val canText = context != null && ready && !busy && !conflict.isBinaryOrTooLarge
        val markerOurs = stringResource(R.string.git_m3_marker_ours)
        val markerTheirs = stringResource(R.string.git_m3_marker_theirs, theirsName)
        val markerEnd = stringResource(R.string.git_m3_marker_end)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Button(stringResource(R.string.git_m3_keep_both), {
                val expected = context ?: return@M3Button
                conflictVM.acceptContent(expected, conflict, keepBoth(conflict, markerOurs, markerTheirs, markerEnd))
            }, Modifier.weight(1f).testTag("conflict-keep-both"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium, enabled = canText)
            M3Button(stringResource(R.string.git_m3_edit_by_hand), {
                val expected = context ?: return@M3Button
                conflictVM.prepareHandEdit(expected, conflict, oursName, theirsName) {
                    handEdited = handEdited + conflict.filePath
                    onNavigate(GitDestination.Editor(conflict.filePath))
                }
            }, Modifier.weight(1f).testTag("conflict-edit-by-hand"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium, enabled = canText)
        }
    }

    if (abort) AbortMergeDialog(workspace.summary?.branch ?: "HEAD", busy = busy, onAbort = {
        abort = false; aborting = true; context?.let { conflictVM.abortMerge(it) }
    }, onDismiss = { abort = false })
    if (error != null) M3Dialog(onDismissRequest = { conflictVM.clearError(context) }, title = stringResource(R.string.tide_git_git_operation),
        icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
        content = { Text(error, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("git-operation-message"), style = M3Type.bodyMedium) }) {
        M3DialogAction(stringResource(R.string.ui_ok_565339bc), { conflictVM.clearError(context) })
    }
}

/** Both versions, this phone's first, each under an HTML comment marker that Markdown hides. */
internal fun keepBoth(conflict: Conflict, ours: String, theirs: String, end: String): String {
    fun block(text: String) = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
    return "<!-- $ours -->\n" + block(if (conflict.hasCurrentVersion) conflict.currentContent else "") +
        "<!-- $theirs -->\n" + block(if (conflict.hasIncomingVersion) conflict.incomingContent else "") +
        "<!-- $end -->\n"
}
