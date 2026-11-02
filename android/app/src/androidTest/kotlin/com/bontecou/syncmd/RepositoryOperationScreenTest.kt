package com.bontecou.syncmd

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.screens.VaultScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.PullViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RepositoryOperationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var workspace: File
    private lateinit var root: File
    private lateinit var model: PullViewModel
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val calls = AtomicInteger()
    @Before fun setup() {
        workspace = File(instrumentation.targetContext.filesDir, "viewmodel-screen-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(workspace).canonicalFile
        val repository = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> {
                calls.incrementAndGet(); entered.complete(Unit); release.await()
                return Result.failure(IOException("Owned pull failure"))
            }
        }
        instrumentation.runOnMainSync { model = PullViewModel(PullService(repository)) }
        compose.setContent { SyncMdTheme { VaultScreen(root.path, onOpenGit = {}, onNavigateBack = {}, viewModel = model) } }
        compose.waitUntil(10_000) { model.status.value != null && !model.isLoading.value }
    }
    @After fun cleanup() {
        release.complete(Unit)
        if (::model.isInitialized) {
            val scope = model.viewModelScope.coroutineContext[Job]!!
            instrumentation.runOnMainSync { ViewModelStore().apply { put("pull", model); clear() } }
            runBlocking { withTimeout(10_000) { scope.join() } }
        }
        if (::root.isInitialized) runBlocking { RepositoryOperationCoordinator.withRepository(root.path) { assertTrue(workspace.deleteRecursively()) } }
    }
    @Test fun vaultPullIsDisabledWhileRunningAndItsFailureCanBeAcknowledgedWithoutRetry() {
        val before = Git.open(root).use { it.repository.resolve("HEAD") }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("vault-pull"))
        compose.onNodeWithTag("vault-pull").performClick()
        runBlocking { withTimeout(10_000) { entered.await() } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("vault-pull"))
        compose.onNodeWithTag("vault-pull").assertIsNotEnabled()
        compose.runOnIdle { model.loadStatus() }
        release.complete(Unit)
        compose.waitUntil(10_000) { !model.isLoading.value && model.errorMessage.value != null }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("vault-pull-error-dismiss"))
        compose.onNodeWithText("Owned pull failure", substring = false).assertIsDisplayed()
        compose.waitForIdle()
        val evidence = File(instrumentation.targetContext.filesDir, "parity-evidence").apply { mkdirs() }
        CommittedScreenshot.save(instrumentation, compose.activity.window.decorView, File(evidence, "vault-pull-owned-error.png"))
        compose.onNodeWithTag("vault-pull-error-dismiss").performClick()
        compose.waitUntil(10_000) { model.errorMessage.value == null }
        assertEquals(1, calls.get()); assertEquals(before, Git.open(root).use { it.repository.resolve("HEAD") })
    }
}
