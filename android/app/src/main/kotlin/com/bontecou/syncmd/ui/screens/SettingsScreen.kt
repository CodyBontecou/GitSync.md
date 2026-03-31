package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.AddRepositoryDialog
import com.bontecou.syncmd.ui.components.RepositoryListDialog
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Settings screen for app configuration and repository management.
 *
 * @param onNavigateToLogin      Navigate to the GitHub OAuth login screen.
 * @param onNavigateToRepoPicker Navigate to the GitHub repo picker screen.
 */
@Composable
fun SettingsScreen(
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRepoPicker: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    githubViewModel: GitHubViewModel = hiltViewModel()
) {
    val selectedRepository by viewModel.selectedRepository.collectAsState()
    val allRepositories by viewModel.allRepositories.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val isLoggedIn by githubViewModel.isLoggedIn.collectAsState()
    val githubUser by githubViewModel.user.collectAsState()
    val cachedLogin by githubViewModel.authManager.cachedLogin.collectAsState()
    val cachedName by githubViewModel.authManager.cachedName.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSelectDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // Error message
        if (errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage!!,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Loading state
        if (isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // Repository Management Section
            Text(
                text = "Repository Management",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Current repository card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Current Repository",
                        style = MaterialTheme.typography.labelMedium
                    )

                    if (selectedRepository.isNotBlank()) {
                        Text(
                            text = selectedRepository,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showSelectDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Change")
                            }

                            OutlinedButton(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add",
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Text("Add")
                            }
                        }
                    } else {
                        Text(
                            text = "No repository selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add",
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Add Repository")
                        }
                    }
                }
            }

            // Saved repositories count
            if (allRepositories.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Saved Repositories: ${allRepositories.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Divider()

            // ── GitHub Section ────────────────────────────────────────────
            Text(
                text = "GitHub",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isLoggedIn) {
                        // ── Logged-in state ───────────────────────────────
                        val displayName = githubUser?.name
                            ?: githubUser?.login
                            ?: cachedName
                            ?: cachedLogin
                            ?: "GitHub User"
                        val loginHandle = githubUser?.login ?: cachedLogin

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Column {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (loginHandle != null) {
                                    Text(
                                        text = "@$loginHandle",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onNavigateToRepoPicker,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Browse Repos")
                            }
                            OutlinedButton(
                                onClick = githubViewModel::signOut,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Sign Out")
                            }
                        }
                    } else {
                        // ── Logged-out state ──────────────────────────────
                        Text(
                            text = "Connect your GitHub account to browse and select repositories.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = onNavigateToLogin,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF24292E),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign in with GitHub")
                        }
                    }
                }
            }

            Divider()

            // ── App Settings Section ──────────────────────────────────────
            // App Settings Section
            Text(
                text = "App Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Dark theme toggle
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Dark Theme",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "Enable dark theme",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = appSettings.isDarkTheme,
                        onCheckedChange = { isDark ->
                            viewModel.updateSettings(appSettings.copy(isDarkTheme = isDark))
                        }
                    )
                }
            }

            // Debug info toggle
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Debug Info",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "Show debug information",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = appSettings.showDebugInfo,
                        onCheckedChange = { showDebug ->
                            viewModel.updateSettings(appSettings.copy(showDebugInfo = showDebug))
                        }
                    )
                }
            }

            Divider()

            // About Section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "App", style = MaterialTheme.typography.labelMedium)
                        Text(text = "Sync.md", style = MaterialTheme.typography.labelMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Version", style = MaterialTheme.typography.labelMedium)
                        Text(text = "1.0.0", style = MaterialTheme.typography.labelMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Build", style = MaterialTheme.typography.labelMedium)
                        Text(text = "Phase 5.6", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    // Dialogs
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
            repositories = allRepositories,
            currentRepository = selectedRepository,
            onSelectRepository = { viewModel.setRepositoryPath(it) },
            onRemoveRepository = { viewModel.removeRepository(it) },
            onDismiss = { showSelectDialog = false }
        )
    }
}
