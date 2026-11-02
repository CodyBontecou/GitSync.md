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
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // A Dialog window re-provides its own context and configuration; carry the
    // caller's over so per-screen locale, direction and font scale still apply.
    val parentContext = androidx.compose.ui.platform.LocalContext.current
    val parentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val parentDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val parentDensity = androidx.compose.ui.platform.LocalDensity.current
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF111113).copy(alpha = if (c.isDark) 0.6f else 0.45f))
                // Raw tap handling, not clickable: clickable would merge the whole
                // sheet into one accessibility node and hide its tags and headings.
                .pointerInput(properties.dismissOnClickOutside) {
                    if (properties.dismissOnClickOutside) detectTapGestures { onDismissRequest() }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(10.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .background(c.paper, RoundedCornerShape(Tide.radiusSheet))
                    .border(1.dp, if (c.isDark) c.heroBorder else Color.Transparent, RoundedCornerShape(Tide.radiusSheet))
                    // Swallow taps so they don't reach the backdrop.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 16.dp)
                    .semantics { if (title != null) paneTitle = title },
            ) {
                // The body scrolls; the actions stay pinned below it, as in AlertDialog,
                // so large text never pushes the confirm button off screen.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (icon != null) Box(Modifier.padding(bottom = 16.dp)) { icon() }
                    if (title != null) TideDisplay(title, size = 26.sp)
                    if (text != null) {
                        Box(Modifier.padding(top = 8.dp)) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides c.inkMid,
                                androidx.compose.material3.LocalTextStyle provides TideType.copy.copy(lineHeight = 21.sp),
                            ) { text() }
                        }
                    }
                    if (content != null) Column(Modifier.padding(top = 16.dp), content = content)
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
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

/** OutlinedTextField colors for Tide: card fill, hairline outline, ink focus. */
@Composable
fun tideFieldColors(): TextFieldColors {
    val c = Tide.colors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.card,
        unfocusedContainerColor = c.card,
        disabledContainerColor = c.well,
        errorContainerColor = c.card,
        focusedBorderColor = c.ink,
        unfocusedBorderColor = c.line,
        disabledBorderColor = c.line,
        errorBorderColor = c.danger,
        focusedLabelColor = c.ink,
        unfocusedLabelColor = c.inkFaint,
        cursorColor = c.ink,
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        focusedPlaceholderColor = c.inkFaint,
        unfocusedPlaceholderColor = c.inkFaint,
        focusedSupportingTextColor = c.inkMid,
        unfocusedSupportingTextColor = c.inkFaint,
    )
}

/** Switch colors for Tide: synced green when on, a quiet well when off. */
@Composable
fun tideSwitchColors(): SwitchColors {
    val c = Tide.colors
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = c.synced,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = c.card,
        uncheckedTrackColor = c.lineSoft,
        uncheckedBorderColor = Color.Transparent,
    )
}
