package com.bontecou.syncmd.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.syncmd.observation.RepositoryObservations
import kotlinx.coroutines.flow.collect

/** Refreshes a visible repository visit on subscription, resume and subsequent invalidations. */
@Composable
fun RepositoryUpdatesEffect(path: String, refresh: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRefresh = rememberUpdatedState(refresh)
    LaunchedEffect(path, lifecycle) {
        if (path.isNotBlank()) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            RepositoryObservations.observe(path).collect { currentRefresh.value() }
        }
    }
}
