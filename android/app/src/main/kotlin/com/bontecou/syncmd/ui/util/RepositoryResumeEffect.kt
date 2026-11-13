package com.bontecou.syncmd.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Revalidates working trees after returning from external editors or background execution. */
@Composable
fun RepositoryResumeEffect(repositoryPath: String, refresh: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRefresh = rememberUpdatedState(refresh)
    DisposableEffect(lifecycle, repositoryPath) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && repositoryPath.isNotBlank()) currentRefresh.value()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
