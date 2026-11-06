package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.background.BackgroundRun
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3CheckboxRow
import com.bontecou.syncmd.ui.theme.M3FilledButton
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.BackgroundSyncViewModel

// ─── Turn on Background Sync (board 18 · "Sync while you sleep") ────────────

/** The two checkboxes turn on pull, or pull and push, then [onTurnedOn] continues to Background Sync to pick vaults. */
@Composable
fun UpgradeScreen(model: BackgroundSyncViewModel, onClose: () -> Unit, onTurnedOn: () -> Unit, onHowItWorks: () -> Unit) {
    val state by model.state.collectAsState()
    var pull by rememberSaveable { mutableStateOf(true) }
    var push by rememberSaveable { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(requested, state.busy, state.failed) {
        if (requested && !state.busy) { requested = false; if (!state.failed) onTurnedOn() }
    }
    UpgradeContent(
        lastRun = state.runs.firstOrNull(), pull = pull, push = push, busy = state.busy, failed = state.failed,
        onPull = { pull = it; if (!it) push = false }, onPush = { push = it; if (it) pull = true },
        onTurnOn = { requested = true; model.modes(pull, push, state.configuration.generation) },
        onClose = onClose, onHowItWorks = onHowItWorks,
    )
}

@Composable
private fun UpgradeContent(lastRun: BackgroundRun?, pull: Boolean, push: Boolean, busy: Boolean, failed: Boolean, onPull: (Boolean) -> Unit, onPush: (Boolean) -> Unit,
    onTurnOn: () -> Unit, onClose: () -> Unit, onHowItWorks: () -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding().testTag("upgrade-screen")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            M3IconButton(R.drawable.ic_m3_close, stringResource(R.string.settings_m3_close), onClose, Modifier.offset(x = (-12).dp), tint = c.on)
            SleepIllustration(lastRun)
            Text(stringResource(R.string.settings_m3_upgrade_title), Modifier.padding(top = 24.dp).semantics { heading() }, style = M3Type.displaySmall, color = c.on)
            Text(stringResource(R.string.settings_m3_upgrade_body), Modifier.padding(top = 8.dp), style = M3Type.body, color = c.onVar)
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Perk(R.drawable.ic_m3_arrow_downward, R.string.settings_m3_perk_pull_title, R.string.settings_m3_perk_pull_body)
                Perk(R.drawable.ic_m3_arrow_upward, R.string.settings_m3_perk_push_title, R.string.settings_m3_perk_push_body)
                Perk(R.drawable.ic_m3_notifications, R.string.settings_m3_perk_nudge_title, R.string.settings_m3_perk_nudge_body)
            }
            M3ListCard(Modifier.padding(top = 16.dp), radius = 16.dp) {
                M3CheckboxRow(stringResource(R.string.settings_m3_upgrade_pull), pull, onPull, Modifier.testTag("upgrade-pull"), enabled = !busy)
                M3CheckboxRow(stringResource(R.string.settings_m3_upgrade_push), push, onPush, Modifier.testTag("upgrade-push"), divider = true, enabled = !busy)
            }
            Text(stringResource(R.string.settings_m3_upgrade_note), Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp), style = M3Type.caption, color = c.onVar)
            if (failed) M3Note(stringResource(R.string.background_error), Modifier.padding(top = 12.dp), tone = M3NoteTone.Warn)
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            M3FilledButton(stringResource(R.string.settings_m3_upgrade_turn_on), onTurnOn, Modifier.fillMaxWidth().testTag("upgrade-turn-on"), size = M3ButtonSize.XLarge,
                enabled = pull && !busy, loading = busy)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                M3TextButton(stringResource(R.string.settings_m3_not_now), onClose)
                M3TextButton(stringResource(R.string.settings_m3_how_it_works), onHowItWorks)
            }
        }
    }
}

/** Inverse card: "locked phone ⇄ GitHub" rail and the last background pass ("03:14 · pulled 2 · pushed 1"). */
@Composable
private fun SleepIllustration(lastRun: BackgroundRun?) {
    val c = M3.colors
    val shape = RoundedCornerShape(28.dp)
    val description = stringResource(R.string.settings_m3_upgrade_illustration)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(c.inv, shape).padding(20.dp).semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_m3_upgrade_locked_phone), style = M3Type.metaSmall, color = c.invMeta)
            Text(stringResource(R.string.settings_m3_how_github), style = M3Type.metaSmall, color = c.invMeta)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).background(c.invAhead, CircleShape))
            Box(Modifier.weight(1f).height(2.dp).background(c.invLine))
            Text("⇄", style = M3Type.meta, color = c.invBehind)
            Box(Modifier.weight(1f).height(2.dp).background(c.invLine))
            Box(Modifier.size(14.dp).border(2.dp, c.invBehind, CircleShape))
        }
        lastRun?.let { run ->
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(run.time))
            val pulled = run.vaults.sumOf { it.pulled }
            val pushed = run.vaults.count { it.pushed }
            Text(stringResource(R.string.settings_m3_upgrade_last_run, time, pulled, pushed), Modifier.fillMaxWidth().padding(top = 10.dp),
                style = M3Type.meta.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), color = c.onInv)
        }
    }
}

@Composable
private fun Perk(icon: Int, title: Int, body: Int) {
    val c = M3.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(c.pc, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { M3Icon(icon, tint = c.onPc, size = 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = M3Type.step.copy(fontWeight = FontWeight.SemiBold), color = c.on)
            Text(stringResource(body), style = M3Type.bodyMedium, color = c.onVar)
        }
    }
}

@Preview(name = "18 · Upgrade", heightDp = 915)
@Composable private fun UpgradePreview() = SyncMdTheme(darkTheme = false) {
    UpgradeContent(BackgroundRun(System.currentTimeMillis(), emptyList()), true, false, false, false, {}, {}, {}, {}, {})
}

@Preview(name = "18 · Upgrade · dark", heightDp = 915)
@Composable private fun UpgradeDarkPreview() = SyncMdTheme(darkTheme = true) { UpgradeContent(null, true, true, false, false, {}, {}, {}, {}, {}) }
