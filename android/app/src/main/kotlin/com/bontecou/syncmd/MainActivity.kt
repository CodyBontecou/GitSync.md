package com.bontecou.syncmd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyncMdTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background
                ) {
                    SyncMdApp()
                }
            }
        }
    }
}
