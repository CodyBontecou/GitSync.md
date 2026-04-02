package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.LocalBrutalColors

/**
 * Destructive-action confirmation dialog matching the iOS RevertConfirmModal.
 *
 * Two modes:
 *  - Single-file: [filename] non-null, [files] empty.
 *  - Revert-all:  [filename] null, [files] lists all changed paths.
 */
@Composable
fun RevertConfirmDialog(
    title: String,
    filename: String?,       // null → revert-all mode
    files: List<String>,     // shown as scrollable file list in revert-all mode
    confirmLabel: String = "Revert",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bc = LocalBrutalColors.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Dim backdrop — Dialog already dims, so just the card sits on top
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Stop click propagation on the card itself
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .background(bc.bg)
                    .border(1.5.dp, bc.border)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
            ) {
                Column {

                    // ── Header ──────────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "⚠",
                            style = TextStyle(
                                fontSize = 16.sp,
                                color = bc.error,
                            )
                        )
                        Text(
                            text = title.uppercase(),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                letterSpacing = 1.5.sp,
                                color = bc.text,
                            )
                        )
                        // × close
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(28.dp)
                                .background(bc.surface)
                                .border(1.dp, bc.borderSoft)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onDismiss,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✕",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = bc.textMid,
                                )
                            )
                        }
                    }

                    BDivider()

                    // ── Body ────────────────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (filename != null) {
                            // Single-file mode
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "📄",
                                    style = TextStyle(fontSize = 13.sp, color = bc.textFaint)
                                )
                                Text(
                                    text = filename,
                                    modifier = Modifier.weight(1f),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = bc.text,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "All local changes to this file will be permanently discarded.",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = bc.textMid,
                                )
                            )
                        } else {
                            // Revert-all mode
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "🗂",
                                    style = TextStyle(fontSize = 13.sp, color = bc.textFaint)
                                )
                                Text(
                                    text = "${files.size} file${if (files.size == 1) "" else "s"} will be discarded",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = bc.text,
                                    )
                                )
                            }

                            // File list (capped at 6 + "and N more")
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bc.surface)
                                    .border(1.dp, bc.borderSoft)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                files.take(6).forEach { path ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "−",
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                color = bc.error.copy(alpha = 0.7f),
                                            )
                                        )
                                        Text(
                                            text = path.substringAfterLast('/').ifEmpty { path },
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                color = bc.textMid,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (files.size > 6) {
                                    Text(
                                        text = "and ${files.size - 6} more…",
                                        modifier = Modifier.padding(start = 16.dp),
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = bc.textFaint,
                                        )
                                    )
                                }
                            }

                            Text(
                                text = "This cannot be undone.",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = bc.textMid,
                                )
                            )
                        }
                    }

                    BDivider()

                    // ── Action Buttons ──────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Cancel
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(bc.surface)
                                .border(1.dp, bc.border)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onDismiss,
                                )
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "CANCEL",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp,
                                    color = bc.text,
                                )
                            )
                        }

                        // Confirm (destructive)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(bc.error)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onConfirm,
                                )
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = confirmLabel.uppercase(),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp,
                                    color = Color.White,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
