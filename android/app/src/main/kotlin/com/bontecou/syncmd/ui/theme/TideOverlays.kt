package com.bontecou.syncmd.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.SwitchColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ─── Tide overlays: bottom-sheet dialogs, field and switch colors ───────────

/**
 * Tide's modal: a paper sheet with 32dp corners floating at the bottom over a
 * dimmed backdrop (iOS TideModalSheet). Slot API mirrors Material AlertDialog
 * so call sites move over one-for-one.
 */
@Composable
fun TideDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: String? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    /** True draws an M3 modal bottom sheet (drag handle, top corners); false a basic dialog. */
    sheet: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // A Dialog window re-provides its own context and configuration; carry the
    // caller's over so per-screen locale, direction and font scale still apply.
    val parentContext = androidx.compose.ui.platform.LocalContext.current
    val parentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val parentDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val parentDensity = androidx.compose.ui.platform.LocalDensity.current
    // Dialog windows report no system-bar insets on recent Android (edge-to-edge), so also pad with the
    // activity's bars; otherwise the lowest action can sit under the navigation bar.
    val activityView = androidx.compose.ui.platform.LocalView.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
      androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalContext provides parentContext,
        androidx.compose.ui.platform.LocalConfiguration provides parentConfiguration,
        androidx.compose.ui.platform.LocalLayoutDirection provides parentDirection,
        androidx.compose.ui.platform.LocalDensity provides parentDensity,
      ) {
        val c = Tide.colors
        val dialogBars = com.bontecou.syncmd.ui.screens.rememberDialogBars(activityView)
        val bars = dialogBars.padding
        val compactHeight = parentConfiguration.screenHeightDp < 480
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                // Raw tap handling, not clickable: clickable would merge the whole
                // sheet into one accessibility node and hide its tags and headings.
                .pointerInput(properties.dismissOnClickOutside) {
                    if (properties.dismissOnClickOutside) detectTapGestures { onDismissRequest() }
                },
            contentAlignment = if (sheet) Alignment.BottomCenter else Alignment.Center,
        ) {
            val shape = if (sheet) RoundedCornerShape(topStart = Tide.radiusSheet, topEnd = Tide.radiusSheet) else RoundedCornerShape(Tide.radiusSheet)
            Column(
                modifier
                    .then(dialogBars.tracker)
                    .statusBarsPadding()
                    .padding(top = bars.calculateTopPadding())
                    // Short windows (landscape phones) keep every action on screen by trimming the outer margin.
                    .then(if (sheet) Modifier else Modifier.navigationBarsPadding().padding(bottom = bars.calculateBottomPadding())
                        .padding(horizontal = 24.dp, vertical = if (compactHeight) 8.dp else 24.dp))
                    .imePadding()
                    .widthIn(min = 280.dp, max = if (sheet) 640.dp else 560.dp)
                    .fillMaxWidth()
                    .background(if (sheet) c.card else c.containerHigh, shape)
                    // Swallow taps so they don't reach the backdrop.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(if (sheet) Modifier.navigationBarsPadding().padding(bottom = bars.calculateBottomPadding()) else Modifier)
                    .padding(start = 24.dp, end = 24.dp, top = if (sheet) 0.dp else if (compactHeight) 16.dp else 24.dp, bottom = if (sheet) 16.dp else if (compactHeight) 8.dp else 20.dp)
                    .semantics { if (title != null) paneTitle = title },
            ) {
                if (sheet) {
                    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 32.dp, height = 4.dp).background(c.lineSoft, RoundedCornerShape(2.dp)))
                    }
                }
                // The body scrolls; the actions stay pinned below it, as in AlertDialog,
                // so large text never pushes the confirm button off screen.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = if (icon != null && !sheet) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    if (icon != null) Box(Modifier.padding(bottom = 16.dp)) { icon() }
                    if (title != null) Text(
                        title,
                        style = TideType.sans(24.sp).copy(lineHeight = 32.sp),
                        color = c.ink,
                        textAlign = if (icon != null && !sheet) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (text != null) {
                        // Bounded so a caller's own scrolling body never meets this scroll's infinite height.
                        Box(Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(max = 4000.dp)) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides c.inkMid,
                                androidx.compose.material3.LocalTextStyle provides TideType.sans(14.sp).copy(lineHeight = 20.sp),
                            ) { text() }
                        }
                    }
                    if (content != null) Column(Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(max = 4000.dp), content = content)
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = if (compactHeight) 12.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    confirmButton()
                    dismissButton?.invoke()
                }
            }
        }
      }
    }
}

/** Ready-made confirmation: icon (when destructive), title, message, pill + Cancel. */
@Composable
fun TideConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    loading: Boolean = false,
) {
    TideDialog(
        onDismissRequest = onDismiss,
        icon = if (destructive) ({ TideModalIcon() }) else null,
        title = title,
        text = { Text(message) },
        confirmButton = {
            if (destructive) BDestructiveButton(confirmLabel, isLoading = loading, onClick = onConfirm)
            else BPrimaryButton(confirmLabel, isLoading = loading, onClick = onConfirm)
        },
        dismissButton = { BGhostButton(cancelLabel, Modifier.fillMaxWidth(), onClick = onDismiss) },
    )
}

/** M3 outlined text field colors: transparent fill, outline at rest, primary when focused. */
@Composable
fun tideFieldColors(): TextFieldColors {
    val c = Tide.colors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        focusedBorderColor = c.primary,
        unfocusedBorderColor = c.lineSoft,
        disabledBorderColor = c.ink.copy(alpha = 0.12f),
        errorBorderColor = c.danger,
        focusedLabelColor = c.primary,
        unfocusedLabelColor = c.inkMid,
        cursorColor = c.primary,
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        focusedPlaceholderColor = c.inkMid,
        unfocusedPlaceholderColor = c.inkMid,
        focusedSupportingTextColor = c.inkMid,
        unfocusedSupportingTextColor = c.inkMid,
    )
}

/** M3 switch colors: primary track when on, outlined well when off. */
@Composable
fun tideSwitchColors(): SwitchColors {
    val c = Tide.colors
    return SwitchDefaults.colors(
        checkedThumbColor = c.onPrimary,
        checkedTrackColor = c.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = c.lineSoft,
        uncheckedTrackColor = c.well,
        uncheckedBorderColor = c.lineSoft,
    )
}
