package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.ObsidianViewModel

@Composable
fun ObsidianScreen(model: ObsidianViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    var forget by remember { mutableStateOf<PullAllTarget?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) forget = null }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler { model.stop(); onBack() }
    ObsidianContent(
        busy = state.busy, notice = state.notice, deliveryFailed = state.deliveryFailed,
        canReturn = state.destination != null && !state.returned, targets = state.targets,
        onBack = { model.stop(); onBack() }, onReturn = model::returnToObsidian, onReload = model::reload,
        onPrepare = { model.prepare(it) }, onForget = { forget = it },
    )
    forget?.let { target ->
        TideDialog(
            onDismissRequest = { forget = null },
            icon = { TideModalIcon() },
            title = stringResource(R.string.obsidian_forget),
            text = {
                Column(Modifier.testTag("obsidian-forget-scroll")) { Text(stringResource(R.string.obsidian_forget_scope)) }
            },
            confirmButton = {
                BDestructiveButton(stringResource(R.string.obsidian_forget), Modifier.testTag("obsidian-forget-confirm"),
                    onClick = { forget = null; model.forget(target) })
            },
            dismissButton = {
                BGhostButton(stringResource(R.string.repository_actions_cancel), Modifier.fillMaxWidth().testTag("obsidian-forget-cancel"),
                    onClick = { forget = null })
            },
        )
    }
}

@Composable
private fun ObsidianContent(
    busy: Boolean,
    notice: String?,
    deliveryFailed: Boolean,
    canReturn: Boolean,
    targets: List<PullAllTarget>,
    onBack: () -> Unit,
    onReturn: () -> Unit,
    onReload: () -> Unit,
    onPrepare: (PullAllTarget) -> Unit,
    onForget: (PullAllTarget) -> Unit,
) {
    val c = Tide.colors
    Scaffold(containerColor = c.paper, topBar = {
        WrappingTopBar(title = stringResource(R.string.obsidian_title), backLabel = stringResource(R.string.pull_all_back),
            onBack = onBack, titleModifier = Modifier.testTag("obsidian-title"), backModifier = Modifier.testTag("obsidian-back"))
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("obsidian-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "scope") {
                Text(stringResource(R.string.obsidian_scope), Modifier.padding(horizontal = 4.dp).testTag("obsidian-scope"),
                    style = TideType.copy, color = c.inkMid)
            }
            item(key = "requirements") {
                val shape = RoundedCornerShape(18.dp)
                Text(stringResource(R.string.obsidian_requirements),
                    Modifier.fillMaxWidth().clip(shape).background(c.well, shape).padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("obsidian-requirements"),
                    style = TideType.sans(13.sp), color = c.inkMid)
            }
            if (busy) item(key = "busy") { SettingsAreaProgress() }
            notice?.let { notice -> item(key = "notice") {
                val tint = when (notice) {
                    "ready" -> c.synced
                    "prepared" -> c.behind
                    "interrupted" -> c.ahead
                    "forgotten" -> c.ink
                    else -> c.danger
                }
                SettingsAreaNotice(stringResource(when (notice) {
                    "prepared" -> R.string.obsidian_prepared
                    "ready" -> R.string.obsidian_ready
                    "forgotten" -> R.string.obsidian_forgotten
                    "interrupted" -> R.string.obsidian_interrupted
                    else -> R.string.obsidian_error
                }), tint = tint, textModifier = Modifier.testTag("obsidian-notice"))
            } }
            if (deliveryFailed) item(key = "delivery") { SettingsAreaNotice(stringResource(R.string.obsidian_return_failed), tint = c.danger) }
            if (canReturn) item(key = "return") {
                BPrimaryButton(stringResource(R.string.obsidian_return), Modifier.testTag("obsidian-return"), onClick = onReturn)
            }
            item(key = "reload") {
                SettingsAreaPill(stringResource(R.string.repository_actions_reload), onReload, Modifier.testTag("obsidian-reload"), enabled = !busy)
            }
            if (targets.isEmpty() && !busy) item(key = "empty") {
                val shape = RoundedCornerShape(Tide.radiusCard)
                Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape).padding(horizontal = 18.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.obsidian_empty), style = TideType.sans(14.sp), color = c.inkMid, textAlign = TextAlign.Center)
                }
            }
            items(targets, key = { it.path }) { target ->
                val shape = RoundedCornerShape(Tide.radiusCard)
                Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape)) {
                    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(target.name, style = TideType.sans(17.sp, FontWeight.SemiBold), color = c.ink)
                        Text(target.path, style = TideType.mono(11.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                    }
                    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                        BPrimaryButton(stringResource(R.string.obsidian_prepare), Modifier.testTag("obsidian-prepare-${target.path}"),
                            isDisabled = busy, onClick = { onPrepare(target) })
                    }
                    SettingsAreaSeparator()
                    SettingsAreaRow(stringResource(R.string.obsidian_forget), Modifier.testTag("obsidian-forget-${target.path}"),
                        titleTint = c.danger, enabled = !busy, onClick = { onForget(target) })
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewTargets = listOf(PullAllTarget("/storage/emulated/0/Documents/Notes", "Notes"))

@Preview(name = "Obsidian · prepared")
@Composable private fun ObsidianPreparedPreview() = SyncMdTheme(darkTheme = false) {
    ObsidianContent(false, "prepared", false, true, previewTargets, {}, {}, {}, {}, {})
}

@Preview(name = "Obsidian · error · dark")
@Composable private fun ObsidianErrorDarkPreview() = SyncMdTheme(darkTheme = true) {
    ObsidianContent(false, "error", true, false, previewTargets, {}, {}, {}, {}, {})
}

@Preview(name = "Obsidian · empty")
@Composable private fun ObsidianEmptyPreview() = SyncMdTheme(darkTheme = false) {
    ObsidianContent(false, null, false, false, emptyList(), {}, {}, {}, {}, {})
}

@Preview(name = "Obsidian · busy · dark")
@Composable private fun ObsidianBusyDarkPreview() = SyncMdTheme(darkTheme = true) {
    ObsidianContent(true, null, false, false, previewTargets, {}, {}, {}, {}, {})
}
