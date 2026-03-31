package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Commit screen for staging files and creating commits.
 */
@Composable
fun CommitScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Commit Changes")
        Text(text = "This screen will display:")
        Text(text = "- List of modified files with status")
        Text(text = "- Diff viewer for selected file")
        Text(text = "- Stage/unstage buttons")
        Text(text = "- Commit message input")
        Text(text = "- Commit button")
    }
}
