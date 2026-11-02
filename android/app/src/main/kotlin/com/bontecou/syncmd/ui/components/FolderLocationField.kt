package com.bontecou.syncmd.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideOutlineButton
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native permission/settings + folder-picker handoff. Permission alone never selects a folder. */
@Composable
fun DeviceFolderButton(label: String, enabled: Boolean = true, onFolder: (Uri) -> Unit, onError: (String) -> Unit, initialUri: Uri? = null,
    primary: Boolean = false) {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onFolder) }
    fun openPicker() {
        val initial = initialUri?.let { if (DocumentsContract.isTreeUri(it)) DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) else it }
        runCatching { picker.launch(initial) }.onFailure { onError(context.getString(com.bontecou.syncmd.R.string.folder_picker_unavailable)) }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (RepositoryLocations.hasSharedStorageAccess(context)) openPicker() else onError(context.getString(com.bontecou.syncmd.R.string.folder_access_not_enabled))
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RepositoryLocations.hasSharedStorageAccess(context)) openPicker() else onError(context.getString(com.bontecou.syncmd.R.string.folder_access_not_enabled))
    }
    val choose = { if (RepositoryLocations.hasSharedStorageAccess(context)) openPicker() else explain = true }
    if (primary) TidePrimaryButton(label, onClick = choose, enabled = enabled, modifier = Modifier.testTag("device-folder-picker"))
    else TideOutlineButton(label, onClick = choose, enabled = enabled, height = 48.dp, modifier = Modifier.testTag("device-folder-picker"))
    if (explain) TideDialog(onDismissRequest = { explain = false },
        icon = { AddVaultIconTile("⌂", size = 48.dp) },
        title = androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_allow_local_folder_access_0b2888fb),
        text = { Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_gitsync_md_needs_shared_storage_access_to_f1b339ea)) },
        confirmButton = { BPrimaryButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_enable_access_9d7caf64), onClick = {
            explain = false
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                runCatching { settings.launch(intent) }.recoverCatching { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    .onFailure { onError(context.getString(com.bontecou.syncmd.R.string.folder_settings_unavailable)) }
            } else permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }) },
        dismissButton = { BGhostButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = { explain = false }) })
}

/** Null means the saved default; APP explicitly chooses app storage for just this clone. */
@Composable
fun FolderLocationField(selection: String?, onSelection: (String?) -> Unit, enabled: Boolean = true, title: String? = null) {
    val context = LocalContext.current
    val locations = remember(context) { RepositoryLocations(context) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val label = runCatching { locations.summary(selection).path }.getOrElse { context.getString(com.bontecou.syncmd.R.string.clone_location_reconnect) }
    TideCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AddVaultIconTile("▤")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title ?: androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.clone_location_title),
                    modifier = Modifier.testTag("clone-location-title").semantics { heading() },
                    style = TideType.sans(16.sp, FontWeight.SemiBold), color = Tide.colors.ink)
                Text(label, style = AddVaultMonoLtr, color = Tide.colors.inkMid, modifier = Modifier.testTag("clone-location-path"))
            }
        }
        Spacer(Modifier.height(14.dp))
        DeviceFolderButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_choose_another_folder_b50df380), enabled && !saving, onFolder = { uri ->
            saving = true; error = null
            scope.launch {
                try {
                    val folder = withContext(Dispatchers.IO) { locations.rememberFolder(uri) }
                    onSelection(folder.uri)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { error = e.message ?: context.getString(com.bontecou.syncmd.R.string.folder_unusable)
                } finally { saving = false }
            }
        }, onError = { error = it }, initialUri = runCatching { locations.selectedFolders().firstOrNull()?.uri?.let(Uri::parse) }.getOrNull())
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selection != RepositoryLocations.APP) AddVaultTextButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_use_app_storage_d199e8e0),
                onClick = { onSelection(RepositoryLocations.APP); error = null }, enabled = enabled && !saving, modifier = Modifier.testTag("clone-location-app"))
            if (selection != null) AddVaultTextButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_use_saved_default_3bb7260d),
                onClick = { onSelection(null); error = null }, enabled = enabled && !saving, modifier = Modifier.testTag("clone-location-default"))
        }
        if (saving) AddVaultProgress(Modifier.padding(top = 8.dp))
        error?.let { AddVaultNote(it, TideBannerTone.DANGER, Modifier.padding(top = 8.dp)) }
    }
}

// ─── Add-a-vault building blocks (Tide) ─────────────────────────────────────
//
// Shared by the onboarding, clone, connection and publication screens. Mirrors
// iOS AddVaultComponents.swift. Internal and prefixed so names never clash.

/** Big display headline with an optional muted second line and body subtitle. */
@Composable
internal fun AddVaultHeadline(
    title: String,
    modifier: Modifier = Modifier,
    muted: String? = null,
    subtitle: String? = null,
    size: TextUnit = 34.sp,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
) {
    val c = Tide.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, modifier = titleModifier.semantics { heading() }, style = TideType.display(size), color = c.ink)
            if (muted != null) Text(muted, style = TideType.display(size), color = c.inkFaint)
        }
        if (subtitle != null) Text(subtitle, modifier = subtitleModifier, style = TideType.sans(15.sp).copy(lineHeight = 21.sp), color = c.inkMid)
    }
}

/** Mono caps section label with an optional mono trailing fact. */
@Composable
internal fun AddVaultSectionLabel(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TideSectionLabel(title, Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = TideType.section, color = Tide.colors.inkFaint)
    }
}

/** Rounded tinted glyph tile used at the start of choice rows and dialogs. */
@Composable
internal fun AddVaultIconTile(glyph: String, tint: Color = Tide.colors.ink, background: Color = Tide.colors.well, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).background(background, RoundedCornerShape(size / 3)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = TideType.sans((size.value * 0.42f).sp, FontWeight.SemiBold), color = tint) }
}

/** Selectable pill (auth method, account). Keeps FilterChip's selected/disabled semantics. */
@Composable
internal fun AddVaultChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Tide.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(c.ink, CircleShape) else Modifier.border(1.dp, c.lineSoft, CircleShape))
            .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TideType.sans(14.sp, if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) c.paper else c.ink, textAlign = TextAlign.Center)
    }
}

/** Body-copy footnote (13/400, faint). */
@Composable
internal fun AddVaultFootnote(text: String, modifier: Modifier = Modifier, color: Color = Tide.colors.inkFaint) {
    Text(text, modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp), style = TideType.sans(13.sp).copy(lineHeight = 18.sp), color = color)
}

/** Inline state line: a tinted dot and a short sentence (warning, error, notice). */
@Composable
internal fun AddVaultNote(text: String, tone: TideBannerTone, modifier: Modifier = Modifier, textModifier: Modifier = Modifier) {
    val c = Tide.colors
    val tint = when (tone) {
        TideBannerTone.DANGER -> c.danger
        TideBannerTone.AHEAD -> c.ahead
        TideBannerTone.BEHIND -> c.behind
        TideBannerTone.SYNCED -> c.synced
        TideBannerTone.NEUTRAL -> c.inkMid
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).background(tint, CircleShape))
        Text(text, textModifier.weight(1f), style = TideType.sans(14.sp, FontWeight.Medium).copy(lineHeight = 19.sp),
            color = if (tone == TideBannerTone.NEUTRAL) c.ink else tint)
    }
}

/** Thin indeterminate progress line in ink (or hero text on the dark hero). */
@Composable
internal fun AddVaultProgress(modifier: Modifier = Modifier, onHero: Boolean = false) {
    val c = Tide.colors
    LinearProgressIndicator(
        modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
        color = if (onHero) c.heroText else c.ink,
        trackColor = if (onHero) c.heroLine else c.line,
    )
}

/** "Working…" line: three pulsing dots and a sentence. */
@Composable
internal fun AddVaultBusyLine(text: String, modifier: Modifier = Modifier, onHero: Boolean = false) {
    val c = Tide.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TidePulse(color = if (onHero) c.heroText else c.ink)
        Text(text, style = TideType.sans(15.sp, FontWeight.Medium), color = if (onHero) c.heroMeta else c.inkMid)
    }
}

/** Text-only action with a 48dp hit target and real enabled semantics. */
@Composable
internal fun AddVaultTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Tide.colors.ink,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TideType.sans(15.sp, FontWeight.Medium), color = color, textAlign = TextAlign.Center)
    }
}

/** Mono LTR style for URLs, paths, hosts and fingerprints. */
internal val AddVaultMonoLtr get() = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr)

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun AddVaultBlocksPreviewContent() {
    Column(Modifier.background(Tide.colors.paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AddVaultHeadline("Clone from URL", muted = "any host.", subtitle = "HTTPS, SSH, git:// or file://.")
        AddVaultSectionLabel("Authentication")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddVaultChoiceChip("Public access", true, {})
            AddVaultChoiceChip("SSH private key", false, {}, enabled = false)
        }
        AddVaultNote("Plain HTTP sends credentials unencrypted.", TideBannerTone.DANGER)
        AddVaultBusyLine("Cloning and preparing the working copy")
        AddVaultProgress()
        AddVaultFootnote("Saved credentials are encrypted on this device.")
    }
}

@Preview(name = "Add-vault blocks · light") @Composable
private fun AddVaultBlocksLight() = SyncMdTheme(darkTheme = false) { AddVaultBlocksPreviewContent() }

@Preview(name = "Add-vault blocks · dark") @Composable
private fun AddVaultBlocksDark() = SyncMdTheme(darkTheme = true) { AddVaultBlocksPreviewContent() }
