package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
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
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideOutlineButton
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.util.asString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

/** The production check: `git ls-remote` plus GitHub metadata, nothing downloaded. */
val defaultVaultPreflight: VaultPreflight by lazy {
    VaultPreflight(com.bontecou.syncmd.services.git.RemoteRefs::list, com.bontecou.syncmd.services.github.GitHubApiService)
}

/**
 * The free vault check (port of iOS `VaultCheckView`). [locked] adds the price to the
 * clone button; the caller's gate asks for the unlock after [onClone].
 */
@Composable
fun VaultCheckScreen(target: VaultTarget, locked: Boolean, price: String?, onClone: () -> Unit, onDismiss: () -> Unit,
    preflight: VaultPreflight = defaultVaultPreflight) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(target) { mutableStateOf(VaultCheckState()) }
    var acceptedReadOnly by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target, attempt) {
        state = VaultCheckState(); acceptedReadOnly = false
        preflight.run(target).flowOn(Dispatchers.IO).collect { state = it }
    }
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentDirection = LocalLayoutDirection.current
    val parentDensity = LocalDensity.current
    val bars = activitySystemBarPadding()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      androidx.compose.runtime.CompositionLocalProvider(LocalContext provides parentContext, LocalConfiguration provides parentConfiguration,
          LocalLayoutDirection provides parentDirection, LocalDensity provides parentDensity) {
        val c = Tide.colors
        val phase = if (acceptedReadOnly && state.phase == VaultPhase.ReadOnly) VaultPhase.Ready else state.phase
        Column(Modifier.fillMaxSize().background(c.paper).padding(bars).testTag("vault-check")) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Tide.gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TideSectionLabel(stringResource(R.string.paywall_check_title))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (pill, tone) = when (phase) {
                        VaultPhase.Checking -> R.string.paywall_pill_checking to c.inkMid
                        VaultPhase.Ready -> R.string.paywall_pill_ready to c.synced
                        VaultPhase.ReadOnly -> R.string.paywall_pill_needs_access to c.ahead
                        is VaultPhase.Unreachable -> R.string.paywall_pill_unreachable to c.danger
                    }
                    TidePill(stringResource(pill), fg = tone, bg = c.well, modifier = Modifier.testTag("vault-check-pill"))
                }
                Text(stringResource(when (phase) {
                    VaultPhase.Checking -> R.string.paywall_check_headline_checking
                    VaultPhase.Ready -> R.string.paywall_check_headline_ready
                    VaultPhase.ReadOnly -> R.string.paywall_check_headline_readonly
                    is VaultPhase.Unreachable -> R.string.paywall_check_headline_unreachable
                }), style = TideType.sans(34.sp, FontWeight.SemiBold).copy(lineHeight = 38.sp), color = c.ink,
                    modifier = Modifier.semantics { heading() }.testTag("vault-check-headline"))
                Text(when (phase) {
                    VaultPhase.Checking -> stringResource(R.string.paywall_check_sub_checking)
                    VaultPhase.Ready -> stringResource(if (acceptedReadOnly) R.string.paywall_check_sub_ready_readonly else R.string.paywall_check_sub_ready)
                    VaultPhase.ReadOnly -> stringResource(R.string.paywall_check_sub_readonly)
                    is VaultPhase.Unreachable -> phase.message.asString()
                }, style = TideType.sans(15.sp).copy(lineHeight = 21.sp), color = c.inkMid, modifier = Modifier.testTag("vault-check-subtitle"))
                TideCard(padding = 16.dp) {
                    Text(target.name, style = TideType.sans(16.sp, FontWeight.SemiBold), color = c.ink)
                    Text(target.url, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint,
                        modifier = Modifier.padding(bottom = 8.dp))
                    VaultCheckKind.entries.forEach { kind -> VaultCheckRowView(kind, state) }
                    if (state.hasObsidianGit) Text(stringResource(R.string.paywall_row_obsidian_git), style = TideType.sans(13.sp), color = c.synced,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Text(when {
                    state.isBlocked && !acceptedReadOnly -> stringResource(if (locked) R.string.paywall_foot_blocked_locked else R.string.paywall_foot_blocked_unlocked)
                    locked -> stringResource(R.string.paywall_foot_locked)
                    else -> stringResource(R.string.paywall_foot_unlocked, stringResource(R.string.paywall_clone_vault))
                }, style = TideType.sans(13.sp).copy(lineHeight = 18.sp), color = c.inkFaint, modifier = Modifier.testTag("vault-check-footnote"))
            }
            Column(Modifier.padding(horizontal = Tide.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (phase) {
                    VaultPhase.Checking -> TidePrimaryButton(stringResource(R.string.paywall_checking_button), onClick = {}, enabled = false, loading = true,
                        modifier = Modifier.testTag("vault-check-busy"))
                    VaultPhase.Ready -> {
                        if (locked) Text(stringResource(R.string.paywall_one_time_line), style = TideType.sans(12.sp), color = c.inkFaint,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        TidePrimaryButton(stringResource(R.string.paywall_clone_vault), onClick = onClone,
                            badge = if (locked) stringResource(R.string.paywall_price_once, price ?: stringResource(R.string.paywall_price_fallback)) else null,
                            modifier = Modifier.testTag("vault-check-clone"))
                    }
                    else -> {
                        TidePrimaryButton(stringResource(R.string.paywall_check_again), onClick = { attempt++ }, modifier = Modifier.testTag("vault-check-again"))
                        if (phase == VaultPhase.ReadOnly) TideOutlineButton(stringResource(R.string.paywall_continue_read_only), onClick = { acceptedReadOnly = true },
                            modifier = Modifier.fillMaxWidth().testTag("vault-check-read-only"))
                        TideOutlineButton(stringResource(R.string.paywall_pick_another), onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("vault-check-pick-another"))
                    }
                }
            }
        }
      }
    }
}

/**
 * The activity window's system-bar insets. Dialog windows here report none (edge-to-edge is
 * enforced on API 35+), so full-screen dialogs pad with the activity's instead.
 */
@Composable
internal fun activitySystemBarPadding(): androidx.compose.foundation.layout.PaddingValues {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val insets = view.rootWindowInsets?.let { androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, view) }
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    return with(density) {
        androidx.compose.foundation.layout.PaddingValues(top = (insets?.top ?: 0).toDp(), bottom = (insets?.bottom ?: 0).toDp())
    }
}

@Composable
private fun VaultCheckRowView(kind: VaultCheckKind, state: VaultCheckState) {
    val c = Tide.colors
    val row = state.row(kind)
    val title = stringResource(when (kind) {
        VaultCheckKind.ACCESS -> R.string.paywall_row_access
        VaultCheckKind.BRANCH -> R.string.paywall_row_branch
        VaultCheckKind.SIZE -> R.string.paywall_row_size
        VaultCheckKind.LFS -> R.string.paywall_row_lfs
        VaultCheckKind.OBSIDIAN -> R.string.paywall_row_obsidian
    })
    val context = LocalContext.current
    val detail = when {
        row.status == VaultCheckStatus.RUNNING -> stringResource(R.string.paywall_row_checking)
        kind == VaultCheckKind.SIZE && row.status == VaultCheckStatus.PASSED -> listOfNotNull(
            state.sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
            state.fileCount?.let { pluralStringResource(R.plurals.paywall_check_files, it, it) },
        ).joinToString(" · ")
        else -> row.detail?.asString().orEmpty()
    }
    val tone = when (row.status) {
        VaultCheckStatus.PASSED -> c.synced
        VaultCheckStatus.WARNING -> c.ahead
        VaultCheckStatus.FAILED -> c.danger
        else -> c.inkFaint
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("vault-check-row-${kind.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(10.dp).background(tone, CircleShape))
        Text(title, style = TideType.sans(15.sp), color = c.ink, modifier = Modifier.weight(1f))
        Text(detail, style = TideType.mono(12.sp), color = if (row.status == VaultCheckStatus.FAILED) c.danger else c.inkMid,
            modifier = Modifier.testTag("vault-check-detail-${kind.name.lowercase()}"))
    }
}
