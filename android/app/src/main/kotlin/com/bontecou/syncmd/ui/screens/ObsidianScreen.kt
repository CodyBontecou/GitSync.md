package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.ui.components.SettingsCard
import com.bontecou.syncmd.ui.components.SettingsIntro
import com.bontecou.syncmd.ui.components.SettingsSectionWithAction
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Divider
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.viewmodels.ObsidianViewModel

// ─── Obsidian connections (board ObsidianLinks: prepared · ready · error · empty · forget) ──

@Composable
fun ObsidianScreen(model: ObsidianViewModel, onAddVault: () -> Unit = {}, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    var forget by remember { mutableStateOf<PullAllTarget?>(null) }
    // The vault the latest request was for; its card carries the connection badge.
    var lastTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) forget = null }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler { model.stop(); onBack() }
    ObsidianContent(
        busy = state.busy, notice = state.notice, deliveryFailed = state.deliveryFailed,
        canReturn = state.destination != null && !state.returned, targets = state.targets, lastTarget = lastTarget,
        onBack = { model.stop(); onBack() }, onReturn = model::returnToObsidian, onReload = model::reload,
        onPrepare = { lastTarget = it.path; model.prepare(it) }, onForget = { forget = it }, onAddVault = onAddVault,
    )
    forget?.let { target ->
        M3Dialog(onDismissRequest = { forget = null }, title = stringResource(R.string.settings_m3_obsidian_forget_title),
            icon = R.drawable.ic_m3_link_off, iconTint = M3.colors.error,
            content = { Column(Modifier.testTag("obsidian-forget-scroll")) { Text(stringResource(R.string.settings_m3_obsidian_forget_body, target.name)) } }) {
            M3DialogAction(stringResource(R.string.repository_actions_cancel), { forget = null }, Modifier.testTag("obsidian-forget-cancel"))
            M3DialogAction(stringResource(R.string.settings_m3_forget), { forget = null; lastTarget = null; model.forget(target) },
                Modifier.testTag("obsidian-forget-confirm"), destructive = true)
        }
    }
}

@Composable
private fun ObsidianContent(
    busy: Boolean,
    notice: String?,
    deliveryFailed: Boolean,
    canReturn: Boolean,
    targets: List<PullAllTarget>,
    lastTarget: String?,
    onBack: () -> Unit,
    onReturn: () -> Unit,
    onReload: () -> Unit,
    onPrepare: (PullAllTarget) -> Unit,
    onForget: (PullAllTarget) -> Unit,
    onAddVault: () -> Unit,
) {
    val c = M3.colors
    val lastName = targets.firstOrNull { it.path == lastTarget }?.name
    Column(Modifier.fillMaxSize().background(c.surface).testTag("obsidian-screen")) {
        M3TopBar(stringResource(R.string.obsidian_title), subtitle = stringResource(R.string.settings_m3_obsidian_subtitle), onBack = onBack,
            modifier = Modifier.testTag("obsidian-title"))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsIntro(stringResource(R.string.settings_m3_obsidian_intro), Modifier.testTag("obsidian-scope"))
            M3Group(stringResource(R.string.settings_m3_obsidian_before), Modifier.testTag("obsidian-requirements")) {
                listOf(
                    Triple(R.drawable.ic_m3_folder, R.string.settings_m3_obsidian_req1_title, R.string.settings_m3_obsidian_req1_body),
                    Triple(R.drawable.ic_m3_widgets, R.string.settings_m3_obsidian_req2_title, R.string.settings_m3_obsidian_req2_body),
                    Triple(R.drawable.ic_m3_lock, R.string.settings_m3_obsidian_req3_title, R.string.settings_m3_obsidian_req3_body),
                    Triple(R.drawable.ic_m3_shield, R.string.settings_m3_obsidian_req4_title, R.string.settings_m3_obsidian_req4_body),
                ).forEachIndexed { index, (icon, title, body) ->
                    M3ListRow(stringResource(title), meta = stringResource(body), metaMono = false, metaStyle = M3Type.bodySmall,
                        leading = { M3Icon(icon) }, divider = index > 0, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            if (busy) M3IndeterminateProgressBar(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
            notice?.let { n ->
                val vault = lastName ?: stringResource(R.string.settings_m3_obsidian_this_vault)
                val (text, tone) = when (n) {
                    "prepared" -> buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.settings_m3_obsidian_prepared_lead)) }
                        append(" "); append(stringResource(R.string.settings_m3_obsidian_prepared_open, vault)); append(" ")
                        withStyle(SpanStyle(fontFamily = M3Type.code.fontFamily, fontSize = 13.sp)) { append(stringResource(R.string.settings_m3_obsidian_command)) }
                        append(stringResource(R.string.settings_m3_obsidian_prepared_tail))
                    } to M3NoteTone.Info
                    "ready" -> buildAnnotatedString { append(stringResource(R.string.settings_m3_obsidian_ready, vault)) } to M3NoteTone.Ok
                    "forgotten" -> buildAnnotatedString { append(stringResource(R.string.obsidian_forgotten)) } to M3NoteTone.Muted
                    "interrupted" -> buildAnnotatedString { append(stringResource(R.string.obsidian_interrupted)) } to M3NoteTone.Ahead
                    else -> buildAnnotatedString { append(stringResource(R.string.settings_m3_obsidian_error)) } to M3NoteTone.Warn
                }
                M3Note(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).testTag("obsidian-notice"), tone = tone,
                    icon = when (tone) { M3NoteTone.Ok -> R.drawable.ic_m3_check; M3NoteTone.Warn -> R.drawable.ic_m3_error; else -> R.drawable.ic_m3_info },
                    action = if (canReturn && n != "error" && !deliveryFailed) ({
                        M3Button(stringResource(R.string.obsidian_return), onReturn, Modifier.testTag("obsidian-return"), icon = R.drawable.ic_m3_open_in_new)
                    }) else null)
            }
            if (deliveryFailed || (canReturn && notice == "error")) M3Note(stringResource(R.string.settings_m3_obsidian_return_failed),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Info, action = if (canReturn || deliveryFailed) ({
                    M3Button(stringResource(R.string.obsidian_return), onReturn, Modifier.testTag("obsidian-return"), icon = R.drawable.ic_m3_open_in_new)
                }) else null)
            SettingsSectionWithAction(stringResource(R.string.settings_m3_obsidian_vaults), stringResource(R.string.repository_actions_reload), onReload,
                Modifier.padding(top = 8.dp).testTag("obsidian-reload"), enabled = !busy)
            if (targets.isEmpty() && !busy) SettingsCard {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        M3Icon(R.drawable.ic_m3_info, tint = c.onVar, size = 20.dp)
                        Text(stringResource(R.string.settings_m3_obsidian_empty), Modifier.weight(1f), style = M3Type.bodyMedium, color = c.onVar)
                    }
                    M3Button(stringResource(R.string.settings_m3_obsidian_add_vault), onAddVault, Modifier.padding(top = 12.dp),
                        style = com.bontecou.syncmd.ui.theme.M3ButtonStyle.Tonal, icon = R.drawable.ic_m3_add)
                }
            }
            targets.forEach { target ->
                SettingsCard(top = 12.dp) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(target.name, Modifier.weight(1f), style = M3Type.bodyStrong.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = c.on)
                            if (target.path == lastTarget) when (notice) {
                                "prepared" -> M3Badge(stringResource(R.string.settings_m3_obsidian_waiting), M3Tone.Ahead)
                                "ready" -> M3Badge(stringResource(R.string.settings_m3_obsidian_connected), M3Tone.Ok)
                                "error" -> M3Badge(stringResource(R.string.settings_m3_obsidian_failed), M3Tone.Warn)
                            }
                        }
                        Text(target.path, Modifier.padding(top = 2.dp), style = M3Type.metaSmall.copy(textDirection = TextDirection.Ltr), color = c.onVar)
                        M3Button(stringResource(R.string.settings_m3_obsidian_prepare), { onPrepare(target) },
                            Modifier.fillMaxWidth().padding(top = 12.dp).testTag("obsidian-prepare-${target.path}"), size = M3ButtonSize.Medium, enabled = !busy)
                    }
                    M3Divider(Modifier.padding(horizontal = 20.dp))
                    M3ListRow(stringResource(R.string.settings_m3_obsidian_forget_action), Modifier.testTag("obsidian-forget-${target.path}"),
                        leading = { M3Icon(R.drawable.ic_m3_link_off, tint = c.error) }, titleColor = c.error, minHeight = 52.dp, enabled = !busy,
                        onClick = { onForget(target) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewTargets = listOf(PullAllTarget("/storage/emulated/0/Documents/Obsidian/VoxVault", "VoxVault"),
    PullAllTarget("/storage/emulated/0/Documents/Obsidian/reading-vault", "reading-vault"))

@Preview(name = "ObsidianLinks · prepared", heightDp = 1300)
@Composable private fun ObsidianPreparedPreview() = SyncMdTheme(darkTheme = false) {
    ObsidianContent(false, "prepared", false, true, previewTargets, previewTargets[0].path, {}, {}, {}, {}, {}, {})
}

@Preview(name = "ObsidianLinks · error · dark", heightDp = 1300)
@Composable private fun ObsidianErrorDarkPreview() = SyncMdTheme(darkTheme = true) {
    ObsidianContent(false, "error", true, false, previewTargets, previewTargets[0].path, {}, {}, {}, {}, {}, {})
}

@Preview(name = "ObsidianLinks · empty")
@Composable private fun ObsidianEmptyPreview() = SyncMdTheme(darkTheme = false) {
    ObsidianContent(false, null, false, false, emptyList(), null, {}, {}, {}, {}, {}, {})
}
