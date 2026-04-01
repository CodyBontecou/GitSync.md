package com.bontecou.syncmd.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.CloneViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Shown while a repository is being cloned at add-time.
 *
 * Matches the iOS loading experience from AppState.clone():
 *  • Starts the clone immediately on composition.
 *  • Navigates to the repo list on success (calling addRepository first).
 *  • Shows the error with a Retry + Cancel option on failure.
 */
@Composable
fun CloneScreen(
    repoFullName: String,
    settingsViewModel: SettingsViewModel,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    viewModel: CloneViewModel = hiltViewModel(),
) {
    val state by viewModel.cloneState.collectAsState()
    val bc    = LocalBrutalColors.current

    // Kick off the clone as soon as we arrive at this screen.
    LaunchedEffect(repoFullName) {
        viewModel.startClone(repoFullName)
    }

    // React to terminal states.
    LaunchedEffect(state) {
        if (state is CloneViewModel.CloneState.Success) {
            val localPath = (state as CloneViewModel.CloneState.Success).localPath
            val repoName  = repoFullName.substringAfterLast("/")
            settingsViewModel.addRepository(
                name  = repoName,
                path  = localPath,
                alias = repoFullName,
            )
            onSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bc.bg)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "CLONING",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 3.sp,
                            color = bc.text,
                        )
                    )
                    // Show Cancel only when not actively cloning.
                    if (state !is CloneViewModel.CloneState.Cloning) {
                        Box(
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = {
                                        viewModel.reset()
                                        onCancel()
                                    },
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "CANCEL",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.sp,
                                    color = bc.textMid,
                                )
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(bc.border)
                )
            }

            // ── Body ──────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (val s = state) {

                    is CloneViewModel.CloneState.Idle,
                    is CloneViewModel.CloneState.Cloning -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            BLoading(text = "Cloning repository")
                            Text(
                                text = repoFullName,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = bc.textFaint,
                                )
                            )
                        }
                    }

                    is CloneViewModel.CloneState.Success -> {
                        // Handled via LaunchedEffect above — this state is transient.
                        BLoading(text = "Finishing up")
                    }

                    is CloneViewModel.CloneState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // Error card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bc.error.copy(alpha = 0.08f))
                                    .border(1.dp, bc.error.copy(alpha = 0.4f))
                                    .padding(16.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "CLONE FAILED",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            letterSpacing = 2.sp,
                                            color = bc.error,
                                        )
                                    )
                                    Text(
                                        text = s.message,
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = bc.error,
                                        )
                                    )
                                }
                            }

                            // Action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // Cancel
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, bc.border)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                            onClick = {
                                                viewModel.reset()
                                                onCancel()
                                            },
                                        )
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "CANCEL",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            letterSpacing = 1.sp,
                                            color = bc.textMid,
                                        )
                                    )
                                }

                                // Retry
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, bc.accent)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                            onClick = { viewModel.startClone(repoFullName) },
                                        )
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "RETRY",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            letterSpacing = 1.sp,
                                            color = bc.accent,
                                        )
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}
