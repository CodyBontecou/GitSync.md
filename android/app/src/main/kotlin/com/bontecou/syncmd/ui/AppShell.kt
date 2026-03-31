package com.bontecou.syncmd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bontecou.syncmd.ui.screens.BranchScreen
import com.bontecou.syncmd.ui.screens.CommitScreen
import com.bontecou.syncmd.ui.screens.ConflictScreen
import com.bontecou.syncmd.ui.screens.HistoryScreen
import com.bontecou.syncmd.ui.screens.RepositoryEmptyState
import com.bontecou.syncmd.ui.screens.SettingsScreen
import com.bontecou.syncmd.ui.screens.StatusScreen
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Main app shell with bottom navigation and navigation routes.
 */
@Composable
fun AppShell() {
    val navController = rememberNavController()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    
    val selectedRepository by settingsViewModel.selectedRepository.collectAsState()
    
    var currentRoute by remember { mutableStateOf("status") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                val items = listOf(
                    BottomNavItem("status", "Status", Icons.Default.CheckCircle),
                    BottomNavItem("commit", "Commit", Icons.Default.Edit),
                    BottomNavItem("branch", "Branch", Icons.Default.Refresh),
                    BottomNavItem("history", "History", Icons.Default.Info),
                    BottomNavItem("conflict", "Merge", Icons.Default.Delete),
                    BottomNavItem("settings", "Settings", Icons.Default.Settings)
                )

                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            currentRoute = item.route
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                fontSize = 10.sp
                            )
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = "status",
                modifier = Modifier.fillMaxSize()
            ) {
                composable("status") {
                    currentRoute = "status"
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = "settings"
                                navController.navigate("settings")
                            }
                        )
                    } else {
                        StatusScreen(repositoryPath = selectedRepository)
                    }
                }

                composable("commit") {
                    currentRoute = "commit"
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = "settings"
                                navController.navigate("settings")
                            }
                        )
                    } else {
                        CommitScreen(repositoryPath = selectedRepository)
                    }
                }

                composable("branch") {
                    currentRoute = "branch"
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = "settings"
                                navController.navigate("settings")
                            }
                        )
                    } else {
                        BranchScreen(repositoryPath = selectedRepository)
                    }
                }

                composable("history") {
                    currentRoute = "history"
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = "settings"
                                navController.navigate("settings")
                            }
                        )
                    } else {
                        HistoryScreen(repositoryPath = selectedRepository)
                    }
                }

                composable("conflict") {
                    currentRoute = "conflict"
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = "settings"
                                navController.navigate("settings")
                            }
                        )
                    } else {
                        ConflictScreen(repositoryPath = selectedRepository)
                    }
                }

                composable("settings") {
                    currentRoute = "settings"
                    SettingsScreen()
                }
            }
        }
    }
}

/**
 * Bottom navigation item data class.
 */
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
