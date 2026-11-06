package com.bontecou.syncmd.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.services.git.GitWorkspaceDetails
import com.bontecou.syncmd.ui.components.AbortMergeDialog
import com.bontecou.syncmd.ui.components.ConflictExcerpt
import com.bontecou.syncmd.ui.components.ConflictExcerpts
import com.bontecou.syncmd.ui.components.gitAgo
import com.bontecou.syncmd.ui.components.gitIsGitHub
import com.bontecou.syncmd.ui.screens.keepBoth
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A conflicted file in the detail pane (boards TabletConflict, FoldConflict), beside the change
 * list: "Conflict i of n · folder/" with Abort merge, the section both sides edited, the two
 * versions side by side ("This tablet" / "GitHub", with times) each with Keep this version, and
 * Keep both / Edit by hand. The logic is the phone Conflict screen's: the same view models, the
 * same keep-both markers, the hand-edit check, and the merge commit after the last file.
 */
@Composable
fun ConflictPane(
    repositoryPath: String,
    filePath: String,
    tablet: Boolean,
    /** The pane moved on to another conflicted file, or (null) none is left. */
    onShowing: (String?) -> Unit,
    /** Edit by hand: open the marked-up file in the editor pane. */
    onEditByHand: (String) -> Unit,
    /** Files edited by hand, kept by the host so the check survives the trip to the editor. */
    handEdited: Set<String>,
    onHandEdited: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    conflictVM: ConflictViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) { conflictVM.setRepositoryPath(repositoryPath); workspaceVM.load(repositoryPath) }
    com.bontecou.syncmd.ui.util.RepositoryResumeEffect(repositoryPath) { conflictVM.loadConflicts() }
    val conflicts by conflictVM.conflicts.collectAsState()
    val resolved by conflictVM.resolvedConflicts.collectAsState()
    val mergeState by conflictVM.mergeState.collectAsState()
    val busy by conflictVM.isLoading.collectAsState()
    val ready by conflictVM.actionsReady.collectAsState()
    val selection by conflictVM.selection.collectAsState()
    val error = conflictVM.errorMessage.collectAsState().value?.asString()
    val context = selection?.takeIf { it.path == repositoryPath }
    val workspace by workspaceVM.state.collectAsState()
    val androidContext = LocalContext.current

    val conflict: Conflict? = conflicts.firstOrNull { it.filePath == filePath } ?: conflicts.firstOrNull()
    LaunchedEffect(conflict?.filePath, ready) { if (ready && conflict?.filePath != filePath) onShowing(conflict?.filePath) }
    var total by rememberSaveable(repositoryPath) { mutableIntStateOf(0) }
    LaunchedEffect(conflicts.size, resolved.size) { total = maxOf(total, conflicts.size + resolved.size) }
    var abort by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var aborting by remember { mutableStateOf(false) }
    LaunchedEffect(conflict?.filePath) { conflict?.let { workspaceVM.loadProvenance(repositoryPath, it.filePath) } }
    val provenance = conflict?.filePath?.let { workspace.provenance[it] } ?: workspace.provenance.values.firstOrNull()
    val source = provenance?.source ?: mergeState?.sourceBranch?.takeIf { it.isNotBlank() }
    val remoteSide = provenance?.sourceIsRemote != false
    val theirsName = if (remoteSide) (if (gitIsGitHub(workspace.summary?.remoteUrl) || workspace.summary == null) stringResource(R.string.git_m3_github)
        else workspace.summary?.remoteUrl?.let { com.bontecou.syncmd.ui.components.gitRemoteDisplay(it)?.substringBefore('/') } ?: stringResource(R.string.git_m3_github))
        else source ?: stringResource(R.string.git_m3_github)
    val oursName = stringResource(if (tablet) R.string.adaptive_this_tablet else R.string.git_m3_this_phone)

    val disk by produceState<String?>(null, conflict, handEdited) {
        value = conflict?.takeIf { it.filePath in handEdited }?.let { c ->
            runCatching { withContext(Dispatchers.IO) { GitWorkspaceDetails.workTreeText(repositoryPath, c.filePath) } }.getOrNull()
        }
    }
    val editedClean = conflict != null && conflict.filePath in handEdited && disk != null &&
        disk!!.lineSequence().none { it.startsWith("<<<<<<<") || it.startsWith(">>>>>>>") }

    // After the last file: make the merge commit (as the phone Conflict screen does).
    LaunchedEffect(conflicts.isEmpty(), ready, mergeState?.isMergeInProgress, total) {
        if (context == null || !ready || conflicts.isNotEmpty()) return@LaunchedEffect
        if (mergeState?.isMergeInProgress == true && !finishing && !aborting) {
            finishing = true
            val message = provenance?.mergeMessage?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: androidContext.getString(R.string.git_m3_merge_message, source ?: theirsName)
            conflictVM.completeMerge(context, message)
        } else if (mergeState?.isMergeInProgress != true) onShowing(null)
    }
    LaunchedEffect(busy, error) { if (!busy && error != null) { finishing = false; aborting = false } }

    val c = M3.colors
    val canAct = context != null && ready && !busy
    val done = (total - conflicts.size).coerceAtLeast(0)
    val index = (done + 1).coerceAtMost(total.coerceAtLeast(1))
    Column(modifier.fillMaxSize().detailPane().testTag("conflict-pane")) {
        DetailHeader(
            conflict?.filePath?.let(::nameOf) ?: nameOf(filePath),
            stringResource(R.string.adaptive_conflict_position, index, total.coerceAtLeast(1), folderOf(conflict?.filePath ?: filePath)),
        ) {
            M3TextButton(stringResource(R.string.adaptive_abort_merge), { abort = true }, Modifier.testTag("conflict-abort"),
                destructive = true, enabled = canAct && mergeState?.isMergeInProgress == true)
        }
        if (conflict == null) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(if (mergeState?.isMergeInProgress == true) R.string.git_m3_finishing_merge else R.string.git_m3_no_conflicts),
                    style = M3Type.title, color = c.on)
                if (busy || finishing) M3IndeterminateProgressBar(Modifier.padding(top = 16.dp))
            }
            return@Column
        }
        val textual = !conflict.isBinaryOrTooLarge && conflict.hasCurrentVersion && conflict.hasIncomingVersion
        val heading = if (textual) ConflictExcerpts.heading(conflict.currentContent, conflict.incomingContent) else null
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            Text(heading?.let { stringResource(R.string.git_m3_both_edited_section, it) } ?: stringResource(when {
                !conflict.hasCurrentVersion -> R.string.git_m3_deleted_here_edited_there
                !conflict.hasIncomingVersion -> R.string.git_m3_edited_here_deleted_there
                conflict.isRenameRename -> R.string.conflict_renamed_differently
                else -> R.string.git_m3_both_edited_file
            }), style = M3Type.title, color = c.on)
            Text(stringResource(R.string.git_m3_conflict_body), Modifier.padding(top = 4.dp), style = M3Type.bodyMedium, color = c.onVar)
            if (conflict.filePath in handEdited) M3Note(stringResource(if (editedClean) R.string.git_m3_hand_edited_ready else R.string.git_m3_hand_edited_markers),
                Modifier.padding(top = 12.dp), tone = if (editedClean) M3NoteTone.Ok else M3NoteTone.Ahead)
        }
        val oursWhen = provenance?.oursTime?.let { stringResource(R.string.git_m3_edited_ago, gitAgo(androidContext, it)) }.orEmpty()
        val theirsWhen = listOfNotNull(provenance?.theirsName?.let { stringResource(R.string.git_m3_from_who, it) },
            provenance?.theirsTime?.let { gitAgo(androidContext, it) }).joinToString(" · ")
        val binary = stringResource(R.string.git_m3_binary_side)
        ConflictSides(
            ours = ConflictSideUi(oursName, oursWhen, if (textual) ConflictExcerpts.excerpt(conflict.currentContent, conflict.incomingContent) else null,
                when { !conflict.hasCurrentVersion -> stringResource(R.string.git_m3_deleted_on_phone); !textual -> binary; else -> null }),
            theirs = ConflictSideUi(theirsName, theirsWhen, if (textual) ConflictExcerpts.excerpt(conflict.incomingContent, conflict.currentContent) else null,
                when { !conflict.hasIncomingVersion -> stringResource(R.string.git_m3_deleted_on_remote, theirsName); !textual -> binary; else -> null }),
            enabled = canAct,
            onKeepOurs = { context?.let { conflictVM.acceptOurs(it, conflict) } },
            onKeepTheirs = { context?.let { conflictVM.acceptTheirs(it, conflict) } },
            modifier = Modifier.weight(1f),
        )
        val markerOurs = stringResource(R.string.git_m3_marker_ours)
        val markerTheirs = stringResource(R.string.git_m3_marker_theirs, theirsName)
        val markerEnd = stringResource(R.string.git_m3_marker_end)
        val canText = canAct && !conflict.isBinaryOrTooLarge
        DetailFooter {
            if (editedClean) M3Button(stringResource(R.string.git_m3_use_edited), {
                context?.let { conflictVM.acceptContent(it, conflict, disk!!) { onHandEdited(handEdited - conflict.filePath) } }
            }, Modifier.testTag("conflict-use-edited"), style = M3ButtonStyle.Filled, size = M3ButtonSize.Medium, enabled = canAct)
            M3Button(stringResource(R.string.git_m3_keep_both), {
                context?.let { conflictVM.acceptContent(it, conflict, keepBoth(conflict, markerOurs, markerTheirs, markerEnd)) }
            }, Modifier.testTag("conflict-keep-both"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium, enabled = canText)
            M3Button(stringResource(R.string.git_m3_edit_by_hand), {
                context?.let { expected ->
                    conflictVM.prepareHandEdit(expected, conflict, oursName, theirsName) {
                        onHandEdited(handEdited + conflict.filePath)
                        onEditByHand(conflict.filePath)
                    }
                }
            }, Modifier.testTag("conflict-edit-by-hand"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium, enabled = canText)
        }
    }

    if (abort) AbortMergeDialog(workspace.summary?.branch ?: "HEAD", busy = busy, onAbort = {
        abort = false; aborting = true; context?.let { conflictVM.abortMerge(it) }
    }, onDismiss = { abort = false })
    if (error != null) M3Dialog(onDismissRequest = { conflictVM.clearError(context) }, title = stringResource(R.string.tide_git_git_operation),
        icon = R.drawable.ic_m3_warning, iconTint = c.error,
        content = { Text(error, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), style = M3Type.bodyMedium) }) {
        M3DialogAction(stringResource(R.string.ui_ok_565339bc), { conflictVM.clearError(context) })
    }
}

/** One side of a conflict for the side-by-side cards. */
data class ConflictSideUi(val who: String, val whenText: String, val excerpt: ConflictExcerpt?, val note: String?)

/**
 * The two versions side by side: this device's card (ringed in primary, filled Keep this
 * version) and the remote's (outlined button), each with its side dot, time and the changed lines.
 */
@Composable
fun ConflictSides(ours: ConflictSideUi, theirs: ConflictSideUi, enabled: Boolean, onKeepOurs: () -> Unit, onKeepTheirs: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SideCard(ours, true, enabled, onKeepOurs, Modifier.weight(1f).fillMaxHeight().testTag("conflict-side-ours"))
        SideCard(theirs, false, enabled, onKeepTheirs, Modifier.weight(1f).fillMaxHeight().testTag("conflict-side-theirs"))
    }
}

@Composable
private fun SideCard(side: ConflictSideUi, ours: Boolean, enabled: Boolean, onKeep: () -> Unit, modifier: Modifier) {
    val c = M3.colors
    val shape = RoundedCornerShape(20.dp)
    val dotColor = if (ours) c.ahead else c.behind
    val (block, mark) = if (ours) c.tcSoft to c.tc else c.pc.copy(alpha = 0.45f) to c.pc
    Column(
        modifier.background(c.cLow, shape).border(if (ours) 2.dp else 1.dp, if (ours) c.primary else c.outlineVar, shape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).then(if (ours) Modifier.background(dotColor, CircleShape) else Modifier.border(2.dp, dotColor, CircleShape)))
            Text(side.who, style = M3Type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.on, maxLines = 1)
        }
        if (side.whenText.isNotEmpty()) Text(side.whenText, Modifier.padding(top = 2.dp), style = M3Type.metaSmall, color = c.onVar, maxLines = 1)
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().weight(1f, fill = false).background(block, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
            val code = M3Type.code.copy(lineHeight = 22.sp)
            if (side.note != null) Text(side.note, style = code, color = c.onVar)
            else side.excerpt?.lines?.forEach { (line, changed) ->
                Text(buildAnnotatedString {
                    if (changed && line.isNotBlank()) withStyle(SpanStyle(background = mark)) { append(line) } else append(line.ifEmpty { " " })
                }, style = code, color = c.on)
            }
        }
        Spacer(Modifier.weight(1f, fill = true).heightIn(min = 0.dp))
        M3Button(stringResource(R.string.adaptive_keep_this_version), onKeep, Modifier.padding(top = 16.dp).fillMaxWidth()
            .testTag(if (ours) "conflict-keep-ours" else "conflict-keep-theirs"),
            style = if (ours) M3ButtonStyle.Filled else M3ButtonStyle.Outlined, size = M3ButtonSize.Medium, enabled = enabled)
    }
}
