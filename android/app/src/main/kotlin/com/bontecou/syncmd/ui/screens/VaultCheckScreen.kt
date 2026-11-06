package com.bontecou.syncmd.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.billing.VaultCheckKind
import com.bontecou.syncmd.billing.VaultCheckState
import com.bontecou.syncmd.billing.VaultCheckStatus
import com.bontecou.syncmd.billing.VaultPhase
import com.bontecou.syncmd.billing.VaultPreflight
import com.bontecou.syncmd.billing.VaultTarget
import com.bontecou.syncmd.services.push.PushInstallation
import com.bontecou.syncmd.ui.components.AddVaultStepBars
import com.bontecou.syncmd.ui.components.addVaultSize
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.util.asString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** The production check: `git ls-remote` plus GitHub metadata, nothing downloaded. */
val defaultVaultPreflight: VaultPreflight by lazy {
    VaultPreflight(com.bontecou.syncmd.services.git.RemoteRefs::list, com.bontecou.syncmd.services.github.GitHubApiService)
}

/** GitHub's settings page for an account's GitHub App installations (used when the installation is unknown). */
private const val INSTALLATIONS_URL = "https://github.com/settings/installations"

/**
 * Paywall boards P1 "Checking the vault", P2 "Your vault is ready", P-alt "One thing to fix first"
 * (port of iOS `VaultCheckView`). [locked] adds the price to the clone button; the caller's gate asks
 * for the unlock (P3, [UnlockSheet]) after [onClone]. [clonesTo] is the destination shown under the stats.
 */
@Composable
fun VaultCheckScreen(target: VaultTarget, locked: Boolean, price: String?, onClone: (detail: String?) -> Unit, onDismiss: () -> Unit,
    preflight: VaultPreflight = defaultVaultPreflight, clonesTo: String? = null) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(target) { mutableStateOf(VaultCheckState()) }
    LaunchedEffect(target, attempt) {
        state = VaultCheckState()
        preflight.run(target).flowOn(Dispatchers.IO).collect { state = it }
    }
    val repo = remember(target) { VaultPreflight.gitHubRepository(target.url) }
    val parentContext = LocalContext.current
    // P-alt: the GitHub App (Push Sync) may be installed on the owner without this repository selected.
    var installation by remember(target) { mutableStateOf<PushInstallation?>(null) }
    LaunchedEffect(target, attempt, state.phase) {
        val owner = repo?.first ?: return@LaunchedEffect
        if (state.phase != VaultPhase.ReadOnly) return@LaunchedEffect
        installation = try {
            withContext(Dispatchers.IO) {
                val backend = dagger.hilt.android.EntryPointAccessors.fromApplication(parentContext.applicationContext,
                    com.bontecou.syncmd.push.PushSyncEntryPoint::class.java).backend()
                backend.status().firstOrNull { it.login.equals(owner, ignoreCase = true) }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val parentConfiguration = LocalConfiguration.current
    val parentDirection = LocalLayoutDirection.current
    val parentDensity = LocalDensity.current
    val bars = activitySystemBarPadding()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      androidx.compose.runtime.CompositionLocalProvider(LocalContext provides parentContext, LocalConfiguration provides parentConfiguration,
          LocalLayoutDirection provides parentDirection, LocalDensity provides parentDensity) {
        VaultCheckContent(target, state, repo?.let { "${it.first}/${it.second}" }, locked, price, clonesTo, installation,
            modifier = Modifier.padding(bars),
            onBack = onDismiss, onClone = onClone, onAgain = { attempt++ },
            onAddOnGitHub = {
                val url = installation?.htmlURL?.takeIf { it.startsWith("https://github.com/") } ?: INSTALLATIONS_URL
                runCatching { parentContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            })
      }
    }
}

@Composable
internal fun VaultCheckContent(
    target: VaultTarget, state: VaultCheckState, fullName: String?, locked: Boolean, price: String?, clonesTo: String?, installation: PushInstallation?,
    modifier: Modifier = Modifier, onBack: () -> Unit, onClone: (String?) -> Unit, onAgain: () -> Unit, onAddOnGitHub: () -> Unit,
) {
    val c = M3.colors
    val context = LocalContext.current
    val phase = state.phase
    val ready = phase == VaultPhase.Ready
    val readOnly = phase == VaultPhase.ReadOnly
    val checking = phase == VaultPhase.Checking
    val step = if (ready) 3 else 2
    val stepLabel = stringResource(R.string.add_check_step, step)
    Column(modifier.fillMaxSize().background(c.surface).testTag("vault-check")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            M3IconButton(R.drawable.ic_arrow_back, stringResource(R.string.publication_back), onBack, tint = c.on)
            Text(stringResource(R.string.add_check_title), style = M3Type.title, color = c.on, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
            Text(stepLabel, style = M3Type.badge, color = c.onVar, modifier = Modifier.padding(end = 12.dp))
        }
        AddVaultStepBars(step, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp), track = c.pc)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(stringResource(when (phase) {
                VaultPhase.Checking -> R.string.add_check_head_checking
                VaultPhase.Ready -> R.string.add_check_head_ready
                VaultPhase.ReadOnly -> R.string.add_check_head_blocked
                is VaultPhase.Unreachable -> R.string.paywall_check_headline_unreachable
            }), style = M3Type.displaySmall, color = c.on, modifier = Modifier.padding(top = 24.dp).semantics { heading() }.testTag("vault-check-headline"))
            Text(when (phase) {
                VaultPhase.Checking -> stringResource(R.string.add_check_sub_checking)
                VaultPhase.Ready -> stringResource(R.string.add_check_sub_ready)
                VaultPhase.ReadOnly -> stringResource(R.string.add_check_sub_blocked)
                is VaultPhase.Unreachable -> phase.message.asString()
            }, style = M3Type.body, color = c.onVar, modifier = Modifier.padding(top = 8.dp).testTag("vault-check-subtitle"))
            M3ListCard(Modifier.padding(top = 20.dp)) {
                Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(target.name, style = M3Type.lead.copy(fontWeight = FontWeight.Medium), color = c.on)
                            Text(listOfNotNull(fullName ?: target.url, state.branch).joinToString(" · "), style = M3Type.meta, color = c.onVar, maxLines = 2)
                        }
                        val (pill, bg, fg) = when (phase) {
                            VaultPhase.Checking -> Triple(R.string.paywall_pill_checking, c.cHighest, c.onVar)
                            VaultPhase.Ready -> Triple(R.string.paywall_pill_ready, c.okc, c.onOkc)
                            VaultPhase.ReadOnly -> Triple(R.string.paywall_pill_needs_access, c.ec, c.onEc)
                            is VaultPhase.Unreachable -> Triple(R.string.paywall_pill_unreachable, c.ec, c.onEc)
                        }
                        Text(stringResource(pill), style = M3Type.metaStrong, color = fg, modifier = Modifier.background(bg, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp).testTag("vault-check-pill"))
                    }
                    if (!ready) Column(Modifier.padding(top = 8.dp)) {
                        val kinds = if (readOnly) listOf(VaultCheckKind.ACCESS, VaultCheckKind.SIZE, VaultCheckKind.OBSIDIAN) else VaultCheckKind.entries
                        kinds.forEach { kind -> VaultCheckRowView(kind, state, compact = readOnly) }
                    }
                    if (ready) VaultCheckStats(state)
                    if (readOnly) Text(
                        if (installation != null && !installation.allRepositories) stringResource(R.string.add_check_app_callout, installation.login)
                        else stringResource(R.string.add_check_app_missing, fullName?.substringBefore('/') ?: target.name),
                        style = M3Type.bodyMedium, color = c.onEc,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).fillMaxWidth().background(c.ec, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp).testTag("vault-check-app-callout"))
                }
            }
            if (ready && clonesTo != null) {
                Text(stringResource(R.string.add_check_clones_to), style = M3Type.caption, color = c.onVar, modifier = Modifier.padding(top = 14.dp))
                Text(clonesTo, style = M3Type.code, color = c.on, modifier = Modifier.padding(top = 2.dp).testTag("vault-check-clones-to"))
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                M3Icon(if (checking) R.drawable.ic_m3_lock else R.drawable.ic_m3_info, tint = c.onVar, size = 20.dp)
                Text(when {
                    checking -> stringResource(R.string.add_check_note_checking)
                    !ready -> stringResource(if (locked) R.string.add_check_note_blocked else R.string.paywall_foot_blocked_unlocked)
                    locked -> stringResource(R.string.add_check_note_ready)
                    else -> stringResource(R.string.add_check_note_ready_unlocked)
                }, style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.weight(1f).testTag("vault-check-footnote"))
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp)) {
            when {
                checking -> M3Button(stringResource(R.string.add_check_checking), {}, Modifier.fillMaxWidth().testTag("vault-check-busy"),
                    size = M3ButtonSize.Hero, enabled = false)
                ready -> {
                    val obsidian = state.row(VaultCheckKind.OBSIDIAN).status == VaultCheckStatus.PASSED
                    VaultCheckCloneButton(stringResource(if (obsidian) R.string.add_check_clone_obsidian else R.string.add_check_clone),
                        if (locked) stringResource(R.string.add_check_price_once, price ?: stringResource(R.string.paywall_price_fallback)) else null) { onClone(state.sizeBytes?.let(::addVaultSize)) }
                    if (locked) Text(stringResource(R.string.add_check_play_note), style = M3Type.bodySmall, color = c.onVar,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                readOnly -> {
                    M3Button(stringResource(R.string.add_check_add_on_github), onAddOnGitHub, Modifier.fillMaxWidth().testTag("vault-check-add-on-github"),
                        size = M3ButtonSize.Hero, icon = null, trailingMeta = null)
                    VaultCheckTextActions(onAgain, onBack)
                }
                else -> {
                    M3Button(stringResource(R.string.add_check_again), onAgain, Modifier.fillMaxWidth().testTag("vault-check-again"), size = M3ButtonSize.Hero)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                        M3Button(stringResource(R.string.add_check_pick_another), onBack, Modifier.testTag("vault-check-pick-another"), style = M3ButtonStyle.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultCheckTextActions(onAgain: () -> Unit, onPickAnother: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        M3Button(stringResource(R.string.add_check_again), onAgain, Modifier.testTag("vault-check-again"), style = M3ButtonStyle.Text)
        M3Button(stringResource(R.string.add_check_pick_another), onPickAnother, Modifier.testTag("vault-check-pick-another"), style = M3ButtonStyle.Text)
    }
}

/** "Clone into Obsidian" with the "$9.99 once" pill inside, right-aligned (P2). */
@Composable
private fun VaultCheckCloneButton(label: String, price: String?, onClick: () -> Unit) {
    val c = M3.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.primary, RoundedCornerShape(28.dp))
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(start = 24.dp, end = 8.dp).testTag("vault-check-clone"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = M3Type.buttonXLarge, color = c.onPrimary, modifier = Modifier.weight(1f))
        if (price != null) Text(price, style = M3Type.button, color = c.primary,
            modifier = Modifier.background(c.onPrimary, RoundedCornerShape(20.dp)).heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp))
        else Spacer(Modifier.size(16.dp))
    }
}

/** P2 stats grid (Size, Files, Access, Git LFS) and the Obsidian lines. */
@Composable
private fun ColumnScope.VaultCheckStats(state: VaultCheckState) {
    val c = M3.colors
    val unknown = stringResource(R.string.add_check_unknown)
    val access = state.row(VaultCheckKind.ACCESS)
    val lfsRow = state.row(VaultCheckKind.LFS)
    val lfsUsed = lfsRow.detail?.asString() == stringResource(R.string.paywall_check_lfs_used)
    val cells = listOf(
        stringResource(R.string.add_check_stat_size) to (state.sizeBytes?.let(::addVaultSize) ?: unknown),
        stringResource(R.string.add_check_stat_files) to (state.fileCount?.let { java.text.NumberFormat.getIntegerInstance().format(it) } ?: unknown),
        stringResource(R.string.add_check_stat_access) to when (access.detail?.asString()) {
            stringResource(R.string.paywall_check_read_only) -> stringResource(R.string.add_check_access_ro)
            stringResource(R.string.paywall_check_read_write) -> stringResource(R.string.add_check_access_rw)
            else -> access.detail?.asString() ?: unknown
        },
        stringResource(R.string.add_check_stat_lfs) to when {
            lfsRow.status != VaultCheckStatus.PASSED -> unknown
            lfsUsed -> stringResource(R.string.add_check_lfs_used)
            else -> stringResource(R.string.add_check_lfs_none)
        },
    )
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).m3TopDivider(true, c.outlineVar).padding(top = 14.dp).testTag("vault-check-stats"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = M3Type.caption, color = c.onVar)
                        Text(value, style = M3Type.lead.copy(fontWeight = FontWeight.Medium), color = c.on)
                    }
                }
            }
        }
    }
    val obsidian = state.row(VaultCheckKind.OBSIDIAN)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).m3TopDivider(true, c.outlineVar).padding(top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (obsidian.status) {
            VaultCheckStatus.PASSED -> VaultCheckLine(stringResource(R.string.add_check_obsidian_found), ".obsidian/")
            VaultCheckStatus.WARNING -> VaultCheckLine(stringResource(R.string.add_check_no_obsidian), null, ok = false)
            else -> VaultCheckLine(stringResource(R.string.paywall_row_obsidian), obsidian.detail?.asString(), ok = false)
        }
        if (state.hasObsidianGit) VaultCheckLine(stringResource(R.string.add_check_obsidian_git), null)
    }
}

@Composable
private fun VaultCheckLine(text: String, code: String?, ok: Boolean = true) {
    val c = M3.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        M3Icon(if (ok) R.drawable.ic_m3_check else R.drawable.ic_m3_info, tint = if (ok) c.ok else c.onVar, size = 18.dp)
        Text(text, style = M3Type.bodyMedium, color = c.on)
        if (code != null) Text(code, style = M3Type.meta, color = c.onVar)
    }
}

/**
 * The activity window's system-bar insets. Dialog windows here report none (edge-to-edge is
 * enforced on API 35+), so full-screen dialogs pad with the activity's instead.
 */
@Composable
internal fun activitySystemBarPadding(): PaddingValues {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val insets = view.rootWindowInsets?.let { androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, view) }
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    return with(density) { PaddingValues(top = (insets?.top ?: 0).toDp(), bottom = (insets?.bottom ?: 0).toDp()) }
}

@Composable
private fun VaultCheckRowView(kind: VaultCheckKind, state: VaultCheckState, compact: Boolean) {
    val c = M3.colors
    val row = state.row(kind)
    val title = stringResource(when (kind) {
        VaultCheckKind.ACCESS -> if (compact) R.string.add_check_write_access else R.string.paywall_row_access
        VaultCheckKind.BRANCH -> R.string.paywall_row_branch
        VaultCheckKind.SIZE -> if (compact) R.string.add_check_size_files else R.string.paywall_row_size
        VaultCheckKind.LFS -> R.string.paywall_row_lfs
        VaultCheckKind.OBSIDIAN -> R.string.paywall_row_obsidian
    })
    val detail = when {
        row.status == VaultCheckStatus.RUNNING -> stringResource(if (kind == VaultCheckKind.SIZE) R.string.add_check_measuring else R.string.paywall_row_checking)
        kind == VaultCheckKind.SIZE && row.status == VaultCheckStatus.PASSED -> listOfNotNull(
            state.sizeBytes?.let(::addVaultSize),
            state.fileCount?.let { java.text.NumberFormat.getIntegerInstance().format(it) },
        ).joinToString(" · ")
        kind == VaultCheckKind.ACCESS && row.status == VaultCheckStatus.PASSED && !compact -> stringResource(R.string.add_check_ok)
        else -> row.detail?.asString().orEmpty()
    }
    val tone = when (row.status) {
        VaultCheckStatus.PASSED -> c.ok
        VaultCheckStatus.WARNING -> c.tertiary
        VaultCheckStatus.FAILED -> c.error
        else -> c.onVar
    }
    Row(Modifier.fillMaxWidth().m3TopDivider(true, c.outlineVar).heightIn(min = 48.dp).testTag("vault-check-row-${kind.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when (row.status) {
            VaultCheckStatus.RUNNING -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = c.primary, trackColor = c.pc, strokeWidth = 3.dp)
            VaultCheckStatus.PASSED -> M3Icon(R.drawable.ic_m3_check, tint = tone, size = 20.dp)
            VaultCheckStatus.FAILED -> M3Icon(R.drawable.ic_m3_close, tint = tone, size = 20.dp)
            VaultCheckStatus.WARNING -> M3Icon(R.drawable.ic_m3_info, tint = tone, size = 20.dp)
            else -> Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).background(c.outlineVar, CircleShape)) }
        }
        val waiting = row.status == VaultCheckStatus.PENDING || row.status == VaultCheckStatus.SKIPPED
        Text(title, style = M3Type.step, color = if (waiting) c.onVar else c.on, modifier = Modifier.weight(1f))
        Text(detail, style = M3Type.meta, color = if (row.status == VaultCheckStatus.PASSED || row.status == VaultCheckStatus.FAILED) tone else c.onVar,
            modifier = Modifier.testTag("vault-check-detail-${kind.name.lowercase()}"))
    }
}
