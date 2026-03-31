package com.bontecou.syncmd

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bontecou.syncmd.ui.screens.CommitScreen
import com.bontecou.syncmd.ui.screens.HistoryScreen
import com.bontecou.syncmd.ui.screens.StatusScreen

/**
 * Main Sync.md application composable with navigation.
 */
@Composable
fun SyncMdApp() {
    val navController = rememberNavController()
    
    Scaffold { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "status",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("status") {
                StatusScreen()
            }
            composable("commit") {
                CommitScreen()
            }
            composable("history") {
                HistoryScreen()
            }
        }
    }
}
