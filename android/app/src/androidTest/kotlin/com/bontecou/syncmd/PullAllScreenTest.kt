package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.bontecou.syncmd.ui.screens.PullAllScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.PullAllViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class PullAllScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var host: Context
    private lateinit var prefix: String
    private lateinit var bare: File
    private lateinit var seed: File
    private val models = mutableListOf<PullAllViewModel>()
    private val shown = mutableStateOf<PullAllViewModel?>(null)
    private val dark = mutableStateOf(false)
    private val large = mutableStateOf(false)
    private var backs = 0

    @Before fun setup() {
        val id = UUID.randomUUID().toString(); prefix = "pull-all-native-$id-"
        workspace = File(app.filesDir, "pull-all-native-$id").canonicalFile.apply { mkdir() }
        host = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        bare = File(workspace, "remote.git")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        seed = File(workspace, "seed"); Git.init().setDirectory(seed).setInitialBranch("main").call().close()
        commit(seed, "Initial bytes"); push()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { shown.value?.let { PullAllScreen(it) { backs++ } } }
            }
        }
    }
    @After fun cleanup() {
        compose.runOnIdle { shown.value = null; models.forEach { model -> ViewModelStore().apply { put("pull", model); clear() } } }
        workspace.deleteRecursively(); app.deleteSharedPreferences(prefix + "sync_md_prefs"); app.deleteSharedPreferences(prefix + PullAllBackgroundRuns.PREFERENCES)
    }
    private fun commit(root: File, text: String) = Git.open(root).use { git ->
        File(root, "note.md").writeText(text); git.add().addFilepattern(".").call()
        git.commit().setMessage(text).setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
    private fun push() = Git.open(seed).use { it.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call() }
    private fun clone(name: String) = File(workspace, name).also { Git.cloneRepository().setDirectory(it).setURI(bare.toURI().toString()).call().close() }
    private fun register(vararg roots: File) {
        val editor = host.getSharedPreferences("sync_md_prefs", 0).edit().clear().putInt("repository_count", roots.size)
        roots.forEachIndexed { i, root -> editor.putString("repo_${i}_name", root.name).putString("repo_${i}_path", root.path).putLong("repo_${i}_date", 17) }
        assertTrue(editor.commit())
    }
    private fun model(pull: PullRepository = LocalPullRepository(), store: PullAllBackgroundRuns? = null, cancel: (String) -> Unit = {}): PullAllViewModel {
        val catalog = PullAllCatalog(host)
        return PullAllViewModel(catalog::snapshot, PullAllRunner(pull, catalog::requireCurrent),
            store?.let { it::latest }, cancel, { store?.clear(); Unit }).also { model ->
            models += model; compose.runOnIdle { shown.value = model }
        }
    }
    private fun finished(model: PullAllViewModel) = compose.waitUntil(15_000) { model.state.value.started && !model.state.value.busy }
    private fun screenshot(name: String) {
        val output = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(output, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun realBatchShowsIndependentResultsPreservesDirtyFilesAndRemoteAndCanRetry() {
        val clean = clone("Clean repository"); val dirty = clone("Dirty repository")
        register(clean, dirty)
        File(dirty, "note.md").writeText("Local bytes to keep")
        val incoming = commit(seed, "Incoming bytes"); push()
        val model = model()
        compose.onNodeWithTag("pull-all-start").performClick(); finished(model)
        assertEquals(listOf(PullAllOutcome.UPDATED, PullAllOutcome.BLOCKED), model.state.value.results.map { it.outcome })
        assertEquals("Incoming bytes", File(clean, "note.md").readText())
        assertEquals("Local bytes to keep", File(dirty, "note.md").readText())
        assertEquals(incoming, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
        compose.onNodeWithText("2 of 2 repositories checked").assertExists()
        screenshot("pull-all-results-light.png")
        compose.onNodeWithTag("pull-all-start").performClick(); finished(model)
        assertEquals(PullAllOutcome.UP_TO_DATE, model.state.value.results.first().outcome)
        compose.onNodeWithContentDescription("Back").performClick(); assertEquals(1, backs)
    }

    @Test fun rapidDuplicateStartAndStopLeaveRemainingRepositoryUntouched() {
        val first = clone("First"); val second = clone("Second"); register(first, second)
        val entered = CompletableDeferred<Unit>(); var calls = 0
        val waiting = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls++; entered.complete(Unit); awaitCancellation() }
        }
        val model = model(waiting)
        compose.runOnIdle { model.start(); model.start() }
        compose.waitUntil(10_000) { entered.isCompleted }
        compose.onNodeWithTag("pull-all-stop").performClick(); finished(model)
        assertTrue(model.state.value.stopped); assertEquals(1, calls)
        assertTrue(model.state.value.results.isEmpty())
        assertEquals("Initial bytes", File(second, "note.md").readText())
        compose.onNodeWithText("Stopped.", substring = true).assertExists()
    }

    @Test fun leavingForegroundStopsTheBatchInsteadOfContinuingIntoOtherRepositories() {
        val first = clone("First"); val second = clone("Second"); register(first, second)
        val entered = CompletableDeferred<Unit>(); var calls = 0
        val waiting = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls++; entered.complete(Unit); awaitCancellation() }
        }
        val model = model(waiting); compose.runOnIdle { model.start() }
        compose.waitUntil(10_000) { entered.isCompleted }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { !model.state.value.busy }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertTrue(model.state.value.stopped); assertEquals(1, calls)
    }

    @Test fun emptyAndMalformedCatalogsHaveDistinctRecoverableStates() {
        register(); val empty = model(); compose.onNodeWithTag("pull-all-start").performClick(); finished(empty)
        compose.onNodeWithText("No local repositories to pull.", substring = true).assertExists()
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1).commit())
        val malformed = model(); compose.onNodeWithTag("pull-all-start").performClick(); finished(malformed)
        assertTrue(malformed.state.value.failed)
        compose.onNodeWithText("Pull All could not start.", substring = true).assertExists()
    }

    @Test fun darkLargeTextLongNamesAndPathsRemainScrollableAndReadable() {
        val root = clone("Long Unicode repository 東京 — notes and documentation with a long display name")
        register(root); val model = model()
        compose.runOnIdle { dark.value = true; large.value = true; model.start() }; finished(model)
        compose.onNodeWithText("No incoming changes").performScrollTo().assertIsDisplayed()
        screenshot("pull-all-results-dark-xl.png")
        compose.onNodeWithTag("pull-all-start").assertIsDisplayed()
    }

    @Test fun savedBackgroundSummaryReviewsWithoutGitAndOnlyConfirmedClearDeletesIt() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve(); assertTrue(store.claim(run.id)); store.targets(run.id, 2)
        store.result(run.id, PullAllResult(PullAllTarget("/not-persisted", "Updated notes"), PullAllOutcome.UPDATED))
        store.result(run.id, PullAllResult(PullAllTarget("/not-persisted", "Dirty notes 東京"), PullAllOutcome.BLOCKED)); store.finish(run.id, BackgroundPullState.COMPLETE)
        var calls = 0
        val noGit = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls++; error("Review must not execute Git") }
        }
        val model = model(noGit, store); compose.runOnIdle { model.open() }
        compose.waitUntil(10_000) { model.state.value.background?.id == run.id }
        assertFalse(model.state.value.started); assertEquals(0, calls)
        compose.onNodeWithTag("pull-all-background-summary").performScrollTo().assertExists()
        screenshot("pull-all-background-summary-light.png")
        compose.runOnIdle { dark.value = true; large.value = true }
        compose.onNodeWithText("Dirty notes 東京").performScrollTo().assertIsDisplayed(); screenshot("pull-all-background-summary-dark-xl.png")
        compose.onNodeWithTag("pull-all-background-clear").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.repository_actions_cancel), substring = false).performClick(); assertNotNull(store.latest())
        compose.onNodeWithTag("pull-all-background-clear").performScrollTo().performClick()
        compose.onNodeWithTag("pull-all-background-clear-confirm").performClick()
        compose.waitUntil(10_000) { model.state.value.background == null }; assertNull(store.latest()); assertEquals(0, calls)
    }
    @Test fun concurrentSummaryRefreshCannotTurnHomeReviewIntoAnotherForegroundPull(): Unit = runBlocking {
        val root = clone("Concurrent review"); register(root)
        val history = BackgroundPullRun(UUID.randomUUID().toString(), System.currentTimeMillis(), BackgroundPullState.COMPLETE)
        val reads = java.util.concurrent.atomic.AtomicInteger(); val pulls = java.util.concurrent.atomic.AtomicInteger()
        val openEntered = java.util.concurrent.CountDownLatch(1); val refreshEntered = java.util.concurrent.CountDownLatch(1)
        val releaseOpen = java.util.concurrent.CountDownLatch(1); val releaseRefresh = java.util.concurrent.CountDownLatch(1)
        val noGit = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { pulls.incrementAndGet(); error("Review must never run Git") }
        }
        val catalog = PullAllCatalog(host)
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(noGit, catalog::requireCurrent), background = {
            when (reads.incrementAndGet()) {
                1 -> null
                2 -> { openEntered.countDown(); check(releaseOpen.await(10, java.util.concurrent.TimeUnit.SECONDS)); history }
                3 -> { refreshEntered.countDown(); check(releaseRefresh.await(10, java.util.concurrent.TimeUnit.SECONDS)); history }
                else -> history
            }
        })
        models += model; compose.runOnIdle { shown.value = model }
        try {
            compose.waitUntil(10_000) { reads.get() >= 1 }
            lateinit var opening: Job
            compose.runOnIdle { opening = model.open() }
            compose.waitUntil(10_000) { openEntered.count == 0L }
            compose.runOnIdle { model.refreshBackground() }
            compose.waitUntil(10_000) { refreshEntered.count == 0L }
            releaseOpen.countDown(); withTimeout(10_000) { opening.join() }
            assertFalse("Review opened a foreground Git run despite its fresh background history", model.state.value.started)
            assertEquals(0, pulls.get())
            releaseRefresh.countDown()
            compose.waitUntil(10_000) { model.state.value.background?.id == history.id }
            assertEquals(0, pulls.get())
        } finally { releaseOpen.countDown(); releaseRefresh.countDown() }
    }

    @Test fun backCancelsPendingHomeReviewBeforeItCanStartAPull(): Unit = runBlocking {
        val root = clone("Pending home review"); register(root)
        val reads = java.util.concurrent.atomic.AtomicInteger(); val pulls = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val catalog = PullAllCatalog(host)
        val noGit = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { pulls.incrementAndGet(); error("Back must cancel pending review") }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(noGit, catalog::requireCurrent), background = {
            if (reads.incrementAndGet() == 2) { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            null
        })
        models += model; compose.runOnIdle { shown.value = model }
        try {
            compose.waitUntil(10_000) { reads.get() >= 1 }
            lateinit var opening: Job
            compose.runOnIdle { opening = model.open() }
            compose.waitUntil(10_000) { entered.count == 0L }
            compose.onNodeWithContentDescription("Back").performClick(); assertEquals(1, backs)
            release.countDown(); withTimeout(10_000) { opening.join() }
            assertFalse("Back allowed a pending home read to start foreground work", model.state.value.started)
            assertEquals(0, pulls.get())
        } finally { release.countDown() }
    }

    @Test fun newerSavedSummarySupersedesAnOlderEmptyHomeRead(): Unit = runBlocking {
        val root = clone("Newer saved summary"); register(root)
        val history = BackgroundPullRun(UUID.randomUUID().toString(), System.currentTimeMillis(), BackgroundPullState.COMPLETE)
        val reads = java.util.concurrent.atomic.AtomicInteger(); val pulls = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val catalog = PullAllCatalog(host)
        val noGit = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { pulls.incrementAndGet(); error("A stale empty read must not authorize Git") }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(noGit, catalog::requireCurrent), background = {
            when (reads.incrementAndGet()) {
                1 -> null
                2 -> { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); null }
                else -> history
            }
        })
        models += model; compose.runOnIdle { shown.value = model }
        try {
            compose.waitUntil(10_000) { reads.get() >= 1 }
            lateinit var opening: Job
            compose.runOnIdle { opening = model.open() }
            compose.waitUntil(10_000) { entered.count == 0L }
            compose.runOnIdle { model.refreshBackground() }
            compose.waitUntil(10_000) { model.state.value.background?.id == history.id }
            release.countDown(); withTimeout(10_000) { opening.join() }
            assertFalse("An older empty read replaced review with another foreground pull", model.state.value.started)
            assertEquals(history.id, model.state.value.background?.id); assertEquals(0, pulls.get())
        } finally { release.countDown() }
    }

    @Test fun homeRecheckReviewsSummaryPublishedAfterEmptyReadWithoutExecutingPull() {
        val root = clone("Summary at recheck"); register(root)
        val history = BackgroundPullRun(UUID.randomUUID().toString(), System.currentTimeMillis(), BackgroundPullState.COMPLETE)
        val reads = java.util.concurrent.atomic.AtomicInteger(); val pulls = java.util.concurrent.atomic.AtomicInteger()
        val catalog = PullAllCatalog(host)
        val noGit = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { pulls.incrementAndGet(); error("Recheck must review newly published history") }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(noGit, catalog::requireCurrent), background = {
            if (reads.incrementAndGet() < 3) null else history
        })
        models += model; compose.runOnIdle { shown.value = model }
        compose.waitUntil(10_000) { reads.get() >= 1 }
        compose.runOnIdle { model.open() }
        compose.waitUntil(10_000) { model.state.value.background?.id == history.id && !model.state.value.busy }
        assertFalse("Home's recheck found history but still ran another pull", model.state.value.started)
        assertEquals(0, pulls.get())
        compose.onNodeWithTag("pull-all-background-summary").performScrollTo().assertIsDisplayed()
    }

    @Test fun explicitPullAllAgainStillRunsAfterReviewingSavedSummary() {
        val root = clone("Explicit repeat after review"); register(root)
        val history = BackgroundPullRun(UUID.randomUUID().toString(), System.currentTimeMillis(), BackgroundPullState.COMPLETE)
        val catalog = PullAllCatalog(host)
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(LocalPullRepository(), catalog::requireCurrent), background = { history })
        models += model; compose.runOnIdle { shown.value = model }
        compose.waitUntil(10_000) { model.state.value.background?.id == history.id }
        assertFalse(model.state.value.started)
        compose.onNodeWithTag("pull-all-start").assertTextEquals(app.getString(R.string.pull_all_again)).performClick()
        finished(model)
        assertEquals(listOf(PullAllOutcome.UP_TO_DATE), model.state.value.results.map { it.outcome })
        assertEquals(history.id, model.state.value.background?.id)
        assertEquals("Initial bytes", File(root, "note.md").readText())
    }

    @Test fun newerRunningSummaryBlocksAManualStartWhoseReadWasSuperseded(): Unit = runBlocking {
        val root = clone("Newer active summary"); register(root)
        val active = BackgroundPullRun(UUID.randomUUID().toString(), System.currentTimeMillis(), BackgroundPullState.RUNNING)
        val reads = java.util.concurrent.atomic.AtomicInteger(); val pulls = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val catalog = PullAllCatalog(host)
        val noGit = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { pulls.incrementAndGet(); error("A running background batch must block another pull") }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(noGit, catalog::requireCurrent), background = {
            when (reads.incrementAndGet()) {
                1 -> null
                2 -> { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); null }
                else -> active
            }
        })
        models += model; compose.runOnIdle { shown.value = model }
        try {
            compose.waitUntil(10_000) { reads.get() >= 1 }
            compose.onNodeWithTag("pull-all-start").performClick()
            compose.waitUntil(10_000) { entered.count == 0L }
            compose.runOnIdle { model.refreshBackground() }
            compose.waitUntil(10_000) { model.state.value.background?.id == active.id }
            release.countDown(); compose.waitUntil(10_000) { !model.state.value.busy }
            assertFalse("A stale manual-start read ignored the newer running batch", model.state.value.started)
            assertEquals(0, pulls.get()); assertEquals(active.id, model.state.value.background?.id)
        } finally { release.countDown() }
    }

    @Test fun homeWithoutBackgroundHistoryStillStartsExactlyOnePull() {
        val root = clone("Empty home history"); register(root)
        val calls = java.util.concurrent.atomic.AtomicInteger(); val local = LocalPullRepository(); val catalog = PullAllCatalog(host)
        val pull = object : PullRepository by local {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls.incrementAndGet(); return local.executePull(repoPath) }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(pull, catalog::requireCurrent), background = { null })
        models += model; compose.runOnIdle { shown.value = model; model.open() }
        finished(model)
        assertEquals(1, calls.get()); assertEquals(listOf(PullAllOutcome.UP_TO_DATE), model.state.value.results.map { it.outcome })
        assertEquals("Initial bytes", File(root, "note.md").readText())
    }

    @Test fun explicitStartReplacesPendingHomeReadWithoutASecondPull(): Unit = runBlocking {
        val root = clone("Explicit start replaces pending review"); register(root)
        val reads = java.util.concurrent.atomic.AtomicInteger(); val calls = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val catalog = PullAllCatalog(host); val local = LocalPullRepository()
        val pull = object : PullRepository by local {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls.incrementAndGet(); return local.executePull(repoPath) }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(pull, catalog::requireCurrent), background = {
            if (reads.incrementAndGet() == 2) { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            null
        })
        models += model; compose.runOnIdle { shown.value = model }
        try {
            compose.waitUntil(10_000) { reads.get() >= 1 }
            lateinit var opening: Job
            compose.runOnIdle { opening = model.open() }
            compose.waitUntil(10_000) { entered.count == 0L }
            compose.onNodeWithTag("pull-all-start").performClick(); finished(model); assertEquals(1, calls.get())
            release.countDown(); withTimeout(10_000) { opening.join() }
            compose.waitUntil(10_000) { !model.state.value.busy }
            assertEquals("Late home read started a second pull after the explicit request finished", 1, calls.get())
        } finally { release.countDown() }
    }

    @Test fun completedSummaryPublishedDuringCatalogReadPreventsAutomaticPull() {
        summaryPublishedDuringCatalogRead(manual = false)
    }

    @Test fun runningSummaryPublishedDuringCatalogReadPreventsManualPull() {
        summaryPublishedDuringCatalogRead(manual = true)
    }

    private fun summaryPublishedDuringCatalogRead(manual: Boolean) {
        val root = clone("Summary while catalog loads"); register(root)
        val beforeHead = Git.open(root).use { it.repository.resolve("HEAD").name }
        val note = File(root, "note.md").readBytes(); val index = File(root, ".git/index").readBytes(); val config = File(root, ".git/config").readBytes()
        val remoteHead = Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger(); val catalog = PullAllCatalog(host); val store = PullAllBackgroundRuns(host)
        val noGit = object : PullRepository by LocalPullRepository() {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls.incrementAndGet(); error("Summary published during catalog load must be checked before Git") }
        }
        val model = PullAllViewModel({
            entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); catalog.snapshot()
        }, PullAllRunner(noGit, catalog::requireCurrent), background = store::latest)
        models += model; compose.runOnIdle { shown.value = model }
        var ownedRun: BackgroundPullRun? = null
        try {
            if (manual) compose.onNodeWithTag("pull-all-start").performClick() else compose.runOnIdle { model.open() }
            compose.waitUntil(10_000) { entered.count == 0L }
            val run = store.reserve(); ownedRun = run; assertTrue(store.claim(run.id)); store.targets(run.id, if (manual) 1 else 0)
            if (!manual) store.finish(run.id, BackgroundPullState.COMPLETE)
            compose.waitUntil(10_000) { model.state.value.background?.let { it.id == run.id && it.state == (if (manual) BackgroundPullState.RUNNING else BackgroundPullState.COMPLETE) } == true }
            release.countDown(); compose.waitUntil(10_000) { !model.state.value.busy }
            assertEquals("Catalog completion dispatched Git after a newer summary was observed", 0, calls.get())
            assertFalse(model.state.value.started); assertEquals(run.id, store.latest()?.id)
            assertEquals(beforeHead, Git.open(root).use { it.repository.resolve("HEAD").name })
            assertEquals(remoteHead, Git.open(bare).use { it.repository.resolve("refs/heads/main").name })
            assertArrayEquals(note, File(root, "note.md").readBytes()); assertArrayEquals(index, File(root, ".git/index").readBytes()); assertArrayEquals(config, File(root, ".git/config").readBytes())
            compose.onNodeWithTag("pull-all-background-summary").performScrollTo().assertIsDisplayed()
        } finally {
            release.countDown()
            ownedRun?.let { run -> try {
                if (store.latest()?.busy == true) store.finish(run.id, BackgroundPullState.INTERRUPTED)
            } finally { store.release(run.id) } }
        }
    }

    @Test fun homeOpenedDuringForegroundWorkDoesNotQueueAnotherPullWhenItsReadFinishesLate(): Unit = runBlocking {
        homeOpenedDuringForegroundWorkDoesNotQueueAnotherPull(stopping = false)
    }

    @Test fun homeOpenedDuringStopCleanupDoesNotRestartTheStoppedBatch(): Unit = runBlocking {
        homeOpenedDuringForegroundWorkDoesNotQueueAnotherPull(stopping = true)
    }

    private suspend fun homeOpenedDuringForegroundWorkDoesNotQueueAnotherPull(stopping: Boolean) {
        val root = clone("Review current foreground batch"); register(root)
        val calls = java.util.concurrent.atomic.AtomicInteger(); val local = LocalPullRepository(); val catalog = PullAllCatalog(host)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val delayReview = java.util.concurrent.atomic.AtomicBoolean(false)
        val reviewEntered = java.util.concurrent.CountDownLatch(1); val releaseReview = java.util.concurrent.CountDownLatch(1)
        val pull = object : PullRepository by local {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    if (stopping) try { awaitCancellation() }
                    finally { withContext(NonCancellable) { finish.await() } }
                    else finish.await()
                }
                return local.executePull(repoPath)
            }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(pull, catalog::requireCurrent), background = {
            if (delayReview.get()) { reviewEntered.countDown(); check(releaseReview.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            null
        })
        models += model; compose.runOnIdle { shown.value = model }
        try {
            compose.onNodeWithTag("pull-all-start").performClick()
            compose.waitUntil(10_000) { entered.isCompleted }
            if (stopping) {
                compose.onNodeWithTag("pull-all-stop").performClick()
                assertTrue(model.state.value.stopping)
            }
            delayReview.set(true)
            lateinit var opening: Job
            compose.runOnIdle { opening = model.open() }
            compose.waitUntil(10_000) { reviewEntered.count == 0L || opening.isCompleted }
            finish.complete(Unit); compose.waitUntil(10_000) { !model.state.value.busy }
            val completed = model.state.value
            delayReview.set(false); releaseReview.countDown(); withTimeout(10_000) { opening.join() }
            compose.waitUntil(10_000) { !model.state.value.busy }
            assertEquals("A Home review queued another pull after the batch finished", 1, calls.get())
            assertEquals(completed.results, model.state.value.results)
            assertEquals(completed.stopped, model.state.value.stopped)
            assertEquals(completed.targets, model.state.value.targets)
            assertEquals("Initial bytes", File(root, "note.md").readText())
            if (stopping) {
                compose.onNodeWithTag("pull-all-start").performClick(); finished(model)
                assertEquals(2, calls.get()); assertFalse(model.state.value.stopped)
                assertEquals(listOf(PullAllOutcome.UP_TO_DATE), model.state.value.results.map { it.outcome })
            }
        } finally { finish.complete(Unit); delayReview.set(false); releaseReview.countDown() }
    }

    @Test fun homeOpenedAfterForegroundCompletionStillStartsANewExplicitPull() {
        val root = clone("Later explicit home entry"); register(root)
        val calls = java.util.concurrent.atomic.AtomicInteger(); val local = LocalPullRepository(); val catalog = PullAllCatalog(host)
        val pull = object : PullRepository by local {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls.incrementAndGet(); return local.executePull(repoPath) }
        }
        val model = PullAllViewModel(catalog::snapshot, PullAllRunner(pull, catalog::requireCurrent), background = { null })
        models += model; compose.runOnIdle { shown.value = model; model.open() }; finished(model)
        assertEquals(1, calls.get())
        compose.runOnIdle { model.open() }
        compose.waitUntil(10_000) { calls.get() == 2 && !model.state.value.busy }
        assertEquals(listOf(PullAllOutcome.UP_TO_DATE), model.state.value.results.map { it.outcome })
        assertEquals("Initial bytes", File(root, "note.md").readText())
    }

    @Test fun homeReviewWhileCatalogLoadsDoesNotDisplaceTheOriginalAutomaticPull(): Unit = runBlocking {
        val root = clone("Review during catalog load"); register(root)
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger(); val local = LocalPullRepository(); val catalog = PullAllCatalog(host)
        val pull = object : PullRepository by local {
            override suspend fun executePull(repoPath: String): Result<SafePullResult> { calls.incrementAndGet(); return local.executePull(repoPath) }
        }
        val model = PullAllViewModel({
            entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); catalog.snapshot()
        }, PullAllRunner(pull, catalog::requireCurrent), background = { null })
        models += model; compose.runOnIdle { shown.value = model }
        try {
            compose.runOnIdle { model.open() }
            compose.waitUntil(10_000) { entered.count == 0L }
            lateinit var review: Job
            compose.runOnIdle { review = model.open() }
            withTimeout(10_000) { review.join() }
            assertTrue(model.state.value.busy)
            release.countDown(); compose.waitUntil(10_000) { !model.state.value.busy }
            assertEquals("Home review displaced the original pending pull", 1, calls.get())
            assertEquals(listOf(PullAllOutcome.UP_TO_DATE), model.state.value.results.map { it.outcome })
            assertEquals("Initial bytes", File(root, "note.md").readText())
        } finally { release.countDown() }
    }

    @Test fun leavingBackgroundSummaryDoesNotStopItsRunAndExplicitStopDoes() {
        val store = PullAllBackgroundRuns(host); val run = store.reserve(); assertTrue(store.claim(run.id)); store.targets(run.id, 1)
        var stops = 0
        try {
            val model = model(store = store, cancel = { id -> assertEquals(run.id, id); stops++; store.finish(id, BackgroundPullState.INTERRUPTED) })
            compose.waitUntil(10_000) { model.state.value.background?.busy == true }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertEquals(0, stops); assertEquals(BackgroundPullState.RUNNING, store.latest()!!.state)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("pull-all-background-stop").performClick()
            compose.waitUntil(10_000) { model.state.value.background?.state == BackgroundPullState.INTERRUPTED }
            assertEquals(1, stops); assertFalse(store.claim(run.id))
        } finally { if (store.latest()?.busy == true) store.finish(run.id, BackgroundPullState.INTERRUPTED) }
    }
    @Test fun unreadableBackgroundSummaryHasExplicitRecoveryWithoutAnAutomaticPull() {
        val prefs = host.getSharedPreferences(PullAllBackgroundRuns.PREFERENCES, 0); assertTrue(prefs.edit().putInt("latest", 7).commit())
        val store = PullAllBackgroundRuns(host); val model = model(store = store)
        compose.runOnIdle { model.open() }; compose.waitUntil(10_000) { model.state.value.backgroundUnavailable }
        assertFalse(model.state.value.started)
        compose.onNodeWithTag("pull-all-background-clear").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.repository_actions_cancel), substring = false).performClick(); assertEquals(7, prefs.getInt("latest", 0))
        compose.onNodeWithTag("pull-all-background-clear").performScrollTo().performClick(); compose.onNodeWithTag("pull-all-background-clear-confirm").performClick()
        compose.waitUntil(10_000) { !model.state.value.backgroundUnavailable }; assertNull(store.latest()); assertFalse(model.state.value.started)
        val partial = store.reserve(); assertTrue(store.claim(partial.id)); store.targets(partial.id, 2)
        store.result(partial.id, PullAllResult(PullAllTarget("/not-persisted", "Completed before failure"), PullAllOutcome.UPDATED))
        store.finish(partial.id, BackgroundPullState.FAILED)
        compose.waitUntil(10_000) { model.state.value.background?.state == BackgroundPullState.FAILED }
        compose.onNodeWithText(app.getString(R.string.pull_all_background_failed)).performScrollTo().assertIsDisplayed()
        assertEquals(1, model.state.value.background!!.checked); assertFalse(model.state.value.started)
    }

    @Test fun systemComponentsDeclarePrivateEntryAndPermissionProtectedTileAndCatalogIsRechecked() {
        val pm = app.packageManager
        val entry = pm.getActivityInfo(ComponentName(app, PullAllActionActivity::class.java), 0)
        assertFalse(entry.exported)
        val tile = pm.getServiceInfo(ComponentName(app, PullAllTileService::class.java), 0)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        assertTrue(android.appwidget.AppWidgetManager.getInstance(app).installedProviders.any {
            it.provider == ComponentName(app, PullAllWidget::class.java)
        })
        val pending = PullAllAction.pendingIntent(app)
        assertEquals(app.packageName, pending.creatorPackage)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(pending.isImmutable)
        val root = clone("Registered"); register(root)
        val catalog = PullAllCatalog(host); val target = catalog.snapshot().single()
        assertTrue(host.getSharedPreferences("sync_md_prefs", 0).edit().putLong("repo_0_date", 18).commit())
        assertTrue(runCatching { catalog.requireCurrent(target) }.isFailure)
    }
}
