package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Status screen displays repository status, working tree changes, and pull options.
 */
@Composable
fun StatusScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Repository Status")
        Text(text = "This screen will display:")
        Text(text = "- Working tree status (modified, staged, untracked)")
        Text(text = "- Current branch and remote tracking info")
        Text(text = "- Commits ahead/behind")
        Text(text = "- Pull/push buttons")
    }
}
