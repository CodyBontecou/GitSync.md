package com.bontecou.syncmd

import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.screens.VaultScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.PullViewModel
import com.bontecou.syncmd.ui.viewmodels.ResolveLocalChangesViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Production Vault composable on a device: a real pull blocked by local edits, iOS-style resolution and URL copy. */
class VaultResolveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var local: File
    private lateinit var pull: PullViewModel
    private lateinit var resolve: ResolveLocalChangesViewModel
    private val merges = AtomicInteger()

    @Before fun setup() {
        workspace = File(context.filesDir, "vault-resolve-${UUID.randomUUID()}").canonicalFile
        val remote = File(workspace, "remote.git"); val seed = File(workspace, "seed"); local = File(workspace, "local")
        Git.init().setBare(true).setDirectory(remote).setInitialBranch("main").call().close()
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(seed).call().use { git ->
            File(seed, "notes.md").writeText("base\n"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Base").setAuthor("Seed", "seed@example.invalid").call(); git.push().setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call()
        }
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(local).setBranch("main").call().use { git ->
            git.repository.config.apply { setString("user", null, "name", "Device Fixture"); setString("user", null, "email", "device@example.invalid"); save() }
        }
        Git.open(seed).use { git ->
            File(seed, "remote.md").writeText("remote\n"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Remote").setAuthor("Seed", "seed@example.invalid").call(); git.push().setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call()
        }
        File(local, "notes.md").writeText("local edit é👍\n"); File(local, "new.md").writeText("untracked\n")
        instrumentation.runOnMainSync {
            pull = PullViewModel(PullService(LocalPullRepository()))
            resolve = ResolveLocalChangesViewModel(DiffService(LocalDiffRepository()), RepositoryAuthors(GitHubAuthManager(context), RepositoryCredentialStore(context)))
        }
        compose.setContent {
            SyncMdTheme {
                VaultScreen(local.path, onOpenGit = {}, onNavigateBack = {}, viewModel = pull, resolveViewModel = resolve,
                    remoteUrl = "https://x-access-token:fixture-secret@github.com/owner/repo.git", onOpenRemoteMerge = { merges.incrementAndGet() })
            }
        }
        compose.waitUntil(15_000) { pull.status.value != null && !pull.isLoading.value }
    }

    @After fun cleanup() {
        if (::pull.isInitialized) {
            val jobs = listOf(pull.viewModelScope.coroutineContext[Job]!!, resolve.viewModelScope.coroutineContext[Job]!!)
            instrumentation.runOnMainSync { ViewModelStore().apply { put("pull", pull); put("resolve", resolve); clear() } }
            runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        }
        if (::local.isInitialized) runBlocking { RepositoryOperationCoordinator.withRepository(local.path) { assertTrue(workspace.deleteRecursively()) } }
    }

    private fun head() = Git.open(local).use { it.repository.resolve("HEAD").name }

    @Test fun blockedPullOffersResolveWhichCommitsLocallyThenOpensTheReviewedMerge() {
        val before = head()
        compose.onNodeWithTag("vault-pull").performScrollTo()
        compose.onNodeWithTag("vault-pull").performClick()
        compose.waitUntil(15_000) { pull.localChangesBlock.value && !pull.isLoading.value }
        assertNull(pull.errorMessage.value)
        assertEquals("Blocked pull leaves HEAD unchanged", before, head())
        compose.onNodeWithText(context.getString(R.string.resolve_local_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.resolve_local_action), ignoreCase = true).performClick()
        compose.onNodeWithTag("resolve-local-message").assertTextContains(context.getString(R.string.resolve_local_default_message))
        compose.waitForIdle()
        val evidence = File(context.filesDir, "parity-evidence").apply { mkdirs() }
        CommittedScreenshot.save(instrumentation, compose.activity.window.decorView, File(evidence, "vault-resolve-sheet.png"))
        compose.onNodeWithTag("resolve-local-confirm").performClick()
        compose.waitUntil(15_000) { merges.get() == 1 }
        Git.open(local).use { git ->
            assertTrue(git.status().call().isClean)
            val commit = git.log().setMaxCount(1).call().single()
            assertEquals(context.getString(R.string.resolve_local_default_message), commit.fullMessage)
            assertEquals("Device Fixture", commit.authorIdent.name)
            assertEquals(before, commit.getParent(0).name)
        }
        assertFalse("Merging stays in the reviewed remote-merge route", File(local, "remote.md").exists())
        compose.waitUntil(10_000) { !pull.localChangesBlock.value }
        compose.onAllNodesWithTag("vault-local-changes-block").assertCountEquals(0)
    }

    @Test fun cancellingResolveCommitsNothing() {
        val before = head()
        compose.onNodeWithTag("vault-pull").performScrollTo()
        compose.onNodeWithTag("vault-pull").performClick()
        compose.waitUntil(15_000) { pull.localChangesBlock.value && !pull.isLoading.value }
        compose.onNodeWithText(context.getString(R.string.resolve_local_action), ignoreCase = true).performClick()
        compose.onNodeWithText(context.getString(R.string.publication_cancel)).performClick()
        compose.onAllNodesWithTag("resolve-local-sheet").assertCountEquals(0)
        assertEquals(before, head()); assertEquals(0, merges.get())
        Git.open(local).use { assertEquals(setOf("notes.md"), it.status().call().modified); assertTrue(it.status().call().added.isEmpty()) }
    }

    @Test fun copyUrlPlacesOnlyTheCredentialFreeAddressOnTheClipboard() {
        compose.onNodeWithContentDescription(context.getString(R.string.tide_vault_more)).performClick()
        compose.onNodeWithTag("vault-copy-url").performClick()
        compose.waitForIdle()
        var copied: String? = null
        instrumentation.runOnMainSync {
            copied = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals("https://github.com/owner/repo.git", copied)
        // The overflow menu also links the per-repository settings that iOS opens from its Vault toolbar.
        compose.onNodeWithContentDescription(context.getString(R.string.tide_vault_more)).performClick()
        listOf("vault-connection", "vault-author", "vault-move").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
    }
}
