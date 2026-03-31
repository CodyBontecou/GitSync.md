package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * History screen for viewing commits, reverting, and managing stashes/tags.
 */
@Composable
fun HistoryScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Commit History")
        Text(text = "This screen will display:")
        Text(text = "- List of recent commits")
        Text(text = "- Commit hash, author, date, message")
        Text(text = "- Revert options")
        Text(text = "- Stash management")
        Text(text = "- Tag operations")
    }
}
