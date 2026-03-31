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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BCardRow
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BMonoRow
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.components.AddRepositoryDialog
import com.bontecou.syncmd.ui.components.RepositoryListDialog
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

@Composable
fun SettingsScreen(
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRepoPicker: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    githubViewModel: GitHubViewModel = hiltViewModel(),
) {
    val bc = LocalBrutalColors.current

    val selectedRepository by viewModel.selectedRepository.collectAsState()
    val allRepositories    by viewModel.allRepositories.collectAsState()
    val appSettings        by viewModel.appSettings.collectAsState()
    val isLoading          by viewModel.isLoading.collectAsState()
    val errorMessage       by viewModel.errorMessage.collectAsState()

    val isLoggedIn  by githubViewModel.isLoggedIn.collectAsState()
    val githubUser  by githubViewModel.user.collectAsState()
    val cachedLogin by githubViewModel.authManager.cachedLogin.collectAsState()
    val cachedName  by githubViewModel.authManager.cachedName.collectAsState()

    var showAddDialog    by remember { mutableStateOf(false) }
    var showSelectDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(bc.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp, vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Error
            if (errorMessage != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bc.error.copy(alpha = 0.08f))
                            .border(1.dp, bc.error.copy(alpha = 0.4f))
                            .padding(12.dp)
                    ) {
                        Text(text = errorMessage!!, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.error))
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        BLoading(text = "Loading")
                    }
                }
            } else {
                // ── Repository Section ────────────────────────────────────
                item { BSectionHeader(title = "Repository") }

                item {
                    BCard {
                        Column {
                            // Current repo row
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "CURRENT REPOSITORY",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        letterSpacing = 1.sp,
                                        color = bc.textMid,
                                    )
                                )

                                if (selectedRepository.isNotBlank()) {
                                    Text(
                                        text = selectedRepository,
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = bc.text,
                                        )
                                    )
                                } else {
                                    Text(
                                        text = "No repository selected",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textFaint)
                                    )
                                }
                            }

                            BDivider()

                            // Action rows
                            if (selectedRepository.isNotBlank()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        BSecondaryButton(
                                            title   = "Change",
                                            onClick = { showSelectDialog = true },
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        BSecondaryButton(
                                            title   = "Add New",
                                            onClick = { showAddDialog = true },
                                        )
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) {
                                    BPrimaryButton(
                                        title   = "+ Add Repository",
                                        onClick = { showAddDialog = true },
                                    )
                                }
                            }

                            if (allRepositories.isNotEmpty()) {
                                BDivider()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "SAVED REPOSITORIES",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp, color = bc.textMid)
                                    )
                                    BBadge(text = "${allRepositories.size}", style = BBadgeStyle.DEFAULT)
                                }
                            }
                        }
                    }
                }

                // ── GitHub Section ────────────────────────────────────────
                item { BSectionHeader(title = "GitHub") }

                item {
                    BCard {
                        Column {
                            if (isLoggedIn) {
                                // Logged-in state
                                val displayName = githubUser?.name
                                    ?: githubUser?.login
                                    ?: cachedName
                                    ?: cachedLogin
                                    ?: "GitHub User"
                                val loginHandle = githubUser?.login ?: cachedLogin

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = displayName,
                                        style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = bc.text)
                                    )
                                    if (loginHandle != null) {
                                        Text(
                                            text = "@$loginHandle",
                                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                                        )
                                    }
                                }
                                BDivider()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        BSecondaryButton(
                                            title   = "Browse Repos",
                                            onClick = onNavigateToRepoPicker,
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        BDestructiveButton(
                                            title   = "Sign Out",
                                            onClick = githubViewModel::signOut,
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = "Connect your GitHub account to browse and sync repositories.",
                                        style = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, color = bc.textMid)
                                    )
                                    BPrimaryButton(
                                        title   = "Sign in with GitHub",
                                        onClick = onNavigateToLogin,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── App Settings Section ──────────────────────────────────
                item { BSectionHeader(title = "App Settings") }

                item {
                    BCard {
                        Column {
                            // Dark theme toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Dark Theme",
                                        style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = bc.text)
                                    )
                                    Text(
                                        text = "Override system appearance",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                                    )
                                }
                                Switch(
                                    checked = appSettings.isDarkTheme,
                                    onCheckedChange = { viewModel.updateSettings(appSettings.copy(isDarkTheme = it)) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor  = bc.bg,
                                        checkedTrackColor  = bc.text,
                                        uncheckedThumbColor = bc.textFaint,
                                        uncheckedTrackColor = bc.surface,
                                        uncheckedBorderColor = bc.border,
                                    )
                                )
                            }

                            BDivider()

                            // Debug info toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Debug Info",
                                        style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = bc.text)
                                    )
                                    Text(
                                        text = "Show debug information",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                                    )
                                }
                                Switch(
                                    checked = appSettings.showDebugInfo,
                                    onCheckedChange = { viewModel.updateSettings(appSettings.copy(showDebugInfo = it)) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor  = bc.bg,
                                        checkedTrackColor  = bc.text,
                                        uncheckedThumbColor = bc.textFaint,
                                        uncheckedTrackColor = bc.surface,
                                        uncheckedBorderColor = bc.border,
                                    )
                                )
                            }
                        }
                    }
                }

                // ── About Section ─────────────────────────────────────────
                item { BSectionHeader(title = "About") }

                item {
                    BCard {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BMonoRow(key = "App",     value = "Sync.md")
                            BDivider()
                            BMonoRow(key = "Platform", value = "Android")
                            BDivider()
                            BMonoRow(key = "Version", value = "1.0.0")
                            BDivider()
                            BMonoRow(key = "Build",   value = "Brutal Edition")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showAddDialog) {
        AddRepositoryDialog(
            onDismiss = { showAddDialog = false },
            onAddRepository = { name, path, alias ->
                viewModel.addRepository(name, path, alias)
                showAddDialog = false
            }
        )
    }
    if (showSelectDialog) {
        RepositoryListDialog(
            repositories   = allRepositories,
            currentRepository = selectedRepository,
            onSelectRepository = { viewModel.setRepositoryPath(it) },
            onRemoveRepository = { viewModel.removeRepository(it) },
            onDismiss = { showSelectDialog = false },
        )
    }
}

@Composable
private fun BDivider() {
    com.bontecou.syncmd.ui.theme.BDivider()
}
