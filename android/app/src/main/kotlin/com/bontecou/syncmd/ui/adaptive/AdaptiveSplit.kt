package com.bontecou.syncmd.ui.adaptive

import com.bontecou.syncmd.ui.util.quantityPhrase
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.CommitSheet
import com.bontecou.syncmd.ui.screens.changeBadge
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel

/**
 * Split screen beside a notes app (board FoldSplit): "watching <vault>" with a live dot, the
 * count of changes as they happen, each file with "typing…" while it is being written, the
 * auto-commit toggle and "Commit & push ↑N" (the commit sheet).
 */
@Composable
fun SplitVaultPanel(
    repositoryPath: String,
    vaultName: String,
    onOpenDiff: (String) -> Unit,
    syncViewModel: VaultSyncViewModel?,
    model: SplitWatchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    // "vault backup: Nov 1, 14:05", the Obsidian Git style the commit sheet also suggests.
    val message = rememberUpdatedState {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd HH:mm")
        context.getString(R.string.vault_m3_suggest_backup, java.text.SimpleDateFormat(pattern, locale).format(java.util.Date()))
    }
    LaunchedEffect(repositoryPath) { model.watch(repositoryPath) { message.value() } }
    LaunchedEffect(repositoryPath) { syncViewModel?.setRepositoryPath(repositoryPath) }
    val state by model.state.collectAsState()
    val auto by model.autoCommit.collectAsState()
    var committing by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    val files = state.files.takeIf { state.path == repositoryPath }.orEmpty()
    SplitVaultContent(
        vaultName = vaultName,
        branch = state.branch,
        ahead = state.ahead,
        rows = files.map { f ->
            val (letter, tone) = changeBadge(f.entry)
            SplitRowUi(f.entry.filePath, letter, tone, f.count?.added, f.count?.removed, AutoCommitPolicy.isTyping(f.modifiedAt, state.now))
        },
        autoCommit = auto,
        onAutoCommit = model::setAutoCommit,
        onOpen = onOpenDiff,
        onCommit = { committing = true },
    )
    if (committing) CommitSheet(repositoryPath, vaultName, onDismiss = { committing = false }, syncViewModel = syncViewModel)
}

data class SplitRowUi(val path: String, val letter: String, val tone: M3Tone, val added: Int?, val removed: Int?, val typing: Boolean)

/** Stateless split-screen panel. */
@Composable
fun SplitVaultContent(
    vaultName: String,
    branch: String?,
    ahead: Int,
    rows: List<SplitRowUi>,
    autoCommit: Boolean,
    onAutoCommit: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onCommit: () -> Unit,
) {
    val c = M3.colors
    val typing = rows.count { it.typing }
    Column(
        Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding().padding(16.dp).testTag("split-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(M3Type.metaStrong.copy(fontSize = 14.sp), Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(c.ok, CircleShape))
            Text(stringResource(R.string.adaptive_split_watching, vaultName), Modifier.padding(start = 6.dp), style = M3Type.meta, color = c.onVar,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth().background(c.tc, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("split-live")) {
            val changePhrase = quantityPhrase(R.plurals.adaptive_split_live, rows.size)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(changePhrase.value, style = M3Type.displayMedium.copy(fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = 0.sp), color = c.onTc)
                Text(changePhrase.label, Modifier.padding(bottom = 8.dp),
                    style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = c.onTc)
            }
            val meta = buildList {
                branch?.let { add(it) }
                val parts = listOfNotNull(
                    ahead.takeIf { it > 0 }?.let { stringResource(R.string.adaptive_split_committed, it) },
                    typing.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.adaptive_split_editing, it, it) },
                )
                if (parts.isNotEmpty()) add(parts.joinToString(", "))
            }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, Modifier.padding(top = 2.dp), style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onTc)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (rows.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.cLow)
            .verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(c.outlineVar))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(row.path) }
                        .padding(horizontal = 16.dp, vertical = 6.dp).testTag("split-file-${row.path}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChangeLetter(row.letter, row.tone, size = 28.dp, radius = 9.dp, fontSize = 12)
                    Column(Modifier.weight(1f)) {
                        Text(nameOf(row.path), style = M3Type.bodyMedium.copy(fontWeight = FontWeight.Medium), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(folderOf(row.path), style = M3Type.metaSmall.copy(textDirection = TextDirection.Ltr), color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (row.typing) Text(stringResource(R.string.adaptive_split_typing), style = M3Type.metaStrong.copy(fontStyle = FontStyle.Italic), color = c.ahead)
                    else ChangeDelta(row.added, row.removed)
                }
            }
        }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(autoCommit, role = Role.Checkbox, onValueChange = onAutoCommit)
                .padding(horizontal = 4.dp).testTag("split-auto-commit"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.adaptive_split_auto_commit), Modifier.weight(1f), style = M3Type.bodyMedium, color = c.on)
            Checkbox(autoCommit, onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.onVar, checkmarkColor = c.onPrimary))
        }
        M3Button(stringResource(R.string.vault_m3_commit_push), onCommit, Modifier.fillMaxWidth().testTag("split-commit-push"),
            style = M3ButtonStyle.Filled, size = M3ButtonSize.XLarge, trailingMeta = rows.size.takeIf { it > 0 }?.let { "↑$it" }, enabled = rows.isNotEmpty())
    }
}
