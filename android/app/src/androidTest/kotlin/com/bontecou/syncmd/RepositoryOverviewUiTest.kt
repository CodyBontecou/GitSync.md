package com.bontecou.syncmd

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.RepositoryOverview
import com.bontecou.syncmd.ui.components.*
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.displayIdentity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Controlled lifecycle/read publication plus real production row layout; no policy or account changes. */
class RepositoryOverviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Gate {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val done=CompletableDeferred<Unit>()
        suspend fun hold() = withContext(NonCancellable) { try { entered.complete(Unit);release.await() } finally { done.complete(Unit) } }
    }
    private val gates=mutableListOf<Gate>()
    private var owner: Owner?=null
    private val ownedFolders=mutableListOf<File>()
    private val ownedRoots=mutableListOf<File>()
    private fun fixture():File {
        val parent=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"overview-live-${java.util.UUID.randomUUID()}")
        ownedFolders+=parent
        return com.bontecou.syncmd.services.git.SampleRepository.openOrCreate(parent).also { ownedRoots+=it }
    }
    private fun owner(): Owner {
        compose.runOnUiThread { owner=Owner().also { it.registry.currentState=Lifecycle.State.RESUMED } }
        return requireNotNull(owner)
    }
    private fun await(gate:Gate)=runBlocking { withTimeout(10_000) { gate.entered.await() } }
    @After fun cleanup() {
        gates.forEach { it.release.complete(Unit) }
        runBlocking { withTimeout(10_000) { gates.filter { it.entered.isCompleted }.forEach { it.done.await() } } }
        compose.runOnUiThread { owner?.registry?.currentState=Lifecycle.State.DESTROYED }
        if(owner!=null)compose.waitForIdle()
        runBlocking {
            for(folder in ownedFolders) com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepositories(
                listOf(folder.path)+ownedRoots.filter { it.parentFile==folder }.map { it.path }) {
                check(folder.name.startsWith("overview-live-")&&!java.nio.file.Files.isSymbolicLink(folder.toPath()))
                if(folder.exists()) java.nio.file.Files.walkFileTree(folder.toPath(),object:java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file:java.nio.file.Path,attributes:java.nio.file.attribute.BasicFileAttributes):java.nio.file.FileVisitResult {
                        java.nio.file.Files.delete(file);return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(directory:java.nio.file.Path,failure:java.io.IOException?):java.nio.file.FileVisitResult {
                        if(failure!=null)throw failure
                        java.nio.file.Files.delete(directory);return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }
    @Composable private fun Probe(path:String, read:suspend (String)->RepositoryOverview, owner: Owner) {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            val result by rememberRepositoryOverview(path,read)
            Text(when(val state=result) {
                is RepositoryOverviewState.Loaded -> state.overview.headCommit.orEmpty()
                RepositoryOverviewState.Loading -> "Loading"
                RepositoryOverviewState.Unavailable -> "Unavailable"
            },Modifier.testTag("probe"))
        }
    }
    @Test fun changedPathCannotPublishAnOlderUncancellableRead() {
        val owner=owner();val path=mutableStateOf("A");val gate=Gate().also(gates::add)
        compose.setContent { Probe(path.value,{ value ->
            if(value=="A")gate.hold()
            RepositoryOverview("main",value,null)
        },owner) }
        await(gate);compose.runOnIdle { path.value="B" }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("B").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { gate.release.complete(Unit) };runBlocking { gate.done.await() };compose.waitForIdle()
        compose.onNodeWithTag("probe").assertTextEquals("B")
    }
    @Test fun resumeReadsAgainAndRecompositionDoesNotRepeatTheRead() {
        val owner=owner();val reads=AtomicInteger();val version=java.util.concurrent.atomic.AtomicReference("before")
        val recompose=mutableStateOf(0)
        compose.setContent { Text("${recompose.value}");Probe("owned",{ reads.incrementAndGet();RepositoryOverview("main",version.get(),null) },owner) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("before").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { recompose.value++ };assertEquals(1,reads.get())
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.CREATED }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("probe") and hasText("Loading",substring=false)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("probe").assertTextEquals("Loading")
        version.set("after")
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("after").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2,reads.get())
    }
    @Test fun failedResumeReadClearsPreviouslyLoadedMetadata() {
        val owner=owner();val fail=java.util.concurrent.atomic.AtomicBoolean()
        compose.setContent { Probe("owned",{ if(fail.get())error("Fixture access unavailable");RepositoryOverview("main","old",null) },owner) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("old").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.CREATED };fail.set(true)
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("probe").assertTextEquals("Unavailable")
    }
    @Test fun metadataRowsWrapWithoutOverflowInLightAndDarkRtlAtLargeText() {
        val dark=mutableStateOf(false)
        val branch="feature/"+"long-branch-name-".repeat(4)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val locale=if(dark.value)Locale.forLanguageTag("ar") else Locale.US
            val localized=remember(locale) { app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {
                setLocales(android.os.LocaleList(locale));setLayoutDirection(locale)
            }) }
            val density=LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides localized.resources.configuration,
                LocalLayoutDirection provides if(dark.value)LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density,1.6f)) {
                SyncMdTheme(darkTheme=dark.value) { Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                        Box(Modifier.width(260.dp)) {
                            RepositoryOverviewRow(branch,"1234567890abcdef",1700000000000,"stress")
                        }
                    }
                } }
            }
        }
        for(theme in listOf(false,true)) {
            compose.runOnIdle { dark.value=theme }
            for(kind in listOf("branch","head","sync")) {
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("stress-$kind").assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(layouts.single().hasVisualOverflow)
            }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            CommittedScreenshot.save(instrumentation,compose.activity.window.decorView,
                File(app.filesDir,"parity-evidence/overview-${if(theme)"dark-rtl" else "light"}-large.png").apply { parentFile!!.mkdirs() })
        }
    }
    @Test fun visibleOverviewRefreshesForItsOwnWritesAndIgnoresOtherRepositories() {
        val root=fixture();val other=fixture();val owner=owner();val otherReads=AtomicInteger()
        compose.setContent { Column {
            Box(Modifier.testTag("live")) { Probe(root.path,{ RepositoryOverview.read(it) },owner) }
            Box(Modifier.testTag("other")) { Probe(other.path,{ otherReads.incrementAndGet();RepositoryOverview("main","other",null) },owner) }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("other").fetchSemanticsNodes().isNotEmpty() }
        val initial=runBlocking { RepositoryOverview.read(root.path).headCommit!! }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(initial).fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            com.bontecou.syncmd.services.git.LocalDiffRepository().stageAll(root.path).getOrThrow()
            com.bontecou.syncmd.services.git.LocalDiffRepository().commit(root.path,"Visible update","Fixture","fixture@example.invalid").getOrThrow()
        }
        val updated=runBlocking { RepositoryOverview.read(root.path).headCommit!! };assertNotEquals(initial,updated)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(updated).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1,otherReads.get())
    }
    @Test fun stoppedOverviewDoesNotReadWritesUntilItsNextResume() {
        val root=fixture();val owner=owner();val reads=AtomicInteger()
        compose.setContent { Probe(root.path,{ reads.incrementAndGet();RepositoryOverview.read(it) },owner) }
        val initial=runBlocking { RepositoryOverview.read(root.path).headCommit!! }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(initial).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.CREATED }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("probe") and hasText("Loading",substring=false)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("probe").assertTextEquals("Loading")
        val before=reads.get()
        runBlocking {
            com.bontecou.syncmd.services.git.LocalDiffRepository().stageAll(root.path).getOrThrow()
            com.bontecou.syncmd.services.git.LocalDiffRepository().commit(root.path,"While stopped","Fixture","fixture@example.invalid").getOrThrow()
        }
        compose.waitForIdle();assertEquals(before,reads.get())
        val updated=runBlocking { RepositoryOverview.read(root.path).headCommit!! }
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(updated).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before+1,reads.get())
    }
    @Test fun directFilesystemCommitRefreshesWhileResumedAndReleasesWatchesWhenStopped() {
        val root=fixture();val owner=owner()
        compose.setContent { Probe(root.path,{ RepositoryOverview.read(it) },owner) }
        val original=org.eclipse.jgit.api.Git.open(root).use { it.repository.resolve("HEAD").name }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty() }
        val updated=org.eclipse.jgit.api.Git.open(root).use { git ->
            File(root,"notes.md").appendText("\nExternal observation fixture\n");git.add().addFilepattern("notes.md").call()
            git.commit().setMessage("External overview change").call().name
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(updated).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.CREATED }
        compose.waitUntil(10_000) { com.bontecou.syncmd.observation.RepositoryObservations.activeRootCount()==0 && com.bontecou.syncmd.observation.RepositoryObservations.watcherCount()==0 }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("probe") and hasText("Loading",substring=false)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("probe").assertTextEquals("Loading")
    }
    @Test fun savedCatalogNamesAndRemoteOwnersNeverComeFromPrivateFolderPaths() {
        fun identity(alias:String="",remote:String?=null,path:String="/data/private/owner-in-path/opaque-id")=
            com.bontecou.syncmd.ui.viewmodels.SavedRepository("Saved notes",path,alias,remoteUrl=remote).displayIdentity()
        assertEquals(com.bontecou.syncmd.ui.util.RepositoryDisplayIdentity("Saved notes",null),identity())
        assertEquals("Personal notes",identity(alias="Personal notes").name)
        assertEquals("team/subgroup",identity(remote="ssh://git@git.example.invalid:2222/team/subgroup/notes.git").owner)
        assertEquals("team",identity(remote="git@git.example.invalid:team/notes.git").owner)
        assertEquals("team",identity(remote="https://fixture:fake-secret@git.example.invalid/team/notes.git?token=fake-query").owner)
        assertEquals("legacy",identity(path="github://legacy/notes").owner)
        assertEquals("legacy",identity(alias="legacy/notes").owner)
        assertNull(identity(remote="https://git.example.invalid/notes.git").owner)
        assertNull(identity(remote="/private/owner/notes").owner)
    }

}
