package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Real private/media filesystems; no catalog, account, permission or global sync policy is changed. */
class BranchCheckoutDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val workspaces = mutableListOf<File>()
    private val roots = mutableListOf<String>()
    private lateinit var privateWorkspace: File
    private lateinit var mediaWorkspace: File
    private lateinit var prefix: String
    @Before fun setup() {
        prefix = "checkout-protection-${UUID.randomUUID()}"
        privateWorkspace = File(context.filesDir, prefix)
        @Suppress("DEPRECATION") val media = context.externalMediaDirs.firstOrNull() ?: error("Owned Android media storage is unavailable")
        mediaWorkspace = File(media, prefix)
        workspaces += listOf(privateWorkspace, mediaWorkspace)
    }
    @After fun cleanup() = runBlocking {
        if (workspaces.none { it.exists() }) return@runBlocking
        RepositoryOperationCoordinator.withRepositories(roots + workspaces.filter { it.exists() }.map { it.path }) {
            for (folder in workspaces.filter { it.exists() }) {
                check(folder.name == prefix && !Files.isSymbolicLink(folder.toPath()))
                Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                    override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                        if (failure != null) throw failure
                        Files.delete(directory); return FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }
    private fun fixture(parent: File, name: String, incoming: String = "private.cache"): File {
        val root = SampleRepository.openOrCreate(File(parent, name)); roots += root.path
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call(); git.checkout().setCreateBranch(true).setName("incoming").call()
            File(root, incoming).apply { parentFile.mkdirs(); writeText("Incoming tracked contents\n") }
            git.add().addFilepattern(".").call(); git.commit().setMessage("Incoming").call(); git.checkout().setName("main").call()
        }
        return root
    }
    private fun exclude(root: File, pattern: String) { File(root, ".git/info/exclude").apply { parentFile.mkdirs(); appendText("\n$pattern\n") } }
    private suspend fun collisions(root: File, contents: File) {
        contents.parentFile.mkdirs(); contents.writeText("Owned private contents\n")
        Git.open(root).use { git ->
            assertTrue(git.status().call().isClean)
            val before = git.repository.resolve("HEAD"); val index = File(root, ".git/index").readBytes(); val repository = LocalBranchRepository()
            assertTrue(repository.switchBranch(root.path, "incoming").isFailure)
            val result = repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF)
            assertTrue(result.isFailure || !result.getOrThrow().success)
            assertEquals(before, git.repository.resolve("HEAD")); assertEquals("main", git.repository.branch)
            assertArrayEquals(index, File(root, ".git/index").readBytes()); assertEquals("Owned private contents\n", contents.readText())
            assertFalse(File(root, ".git/MERGE_HEAD").exists())
        }
    }
    @Test fun ignoredFilesBlockSwitchAndMergeInPrivateAndMediaStorage() = runBlocking {
        for (parent in listOf(privateWorkspace, mediaWorkspace)) {
            val root = fixture(parent, "file"); exclude(root, "private.cache")
            collisions(root, File(root, "private.cache"))
        }
    }
    @Test fun mediaDirectoryAndFilePrefixCollisionsKeepAllLocalContents() = runBlocking {
        val directory = fixture(mediaWorkspace, "directory", "cache"); exclude(directory, "cache/")
        collisions(directory, File(directory, "cache/private.md"))
        val prefix = fixture(mediaWorkspace, "prefix", "cache/incoming.md"); exclude(prefix, "cache")
        collisions(prefix, File(prefix, "cache"))
    }
    @Test fun lateAuthorBranchSwitchCannotRetargetAMergeOnAndroid() = runBlocking {
        val root = fixture(privateWorkspace, "late", "incoming.md")
        val before = Git.open(root).use { it.branchCreate().setName("replacement").call(); it.repository.resolve("HEAD") }
        val repository = LocalBranchRepository(authorResolver = {
            Git.open(root).use { it.checkout().setName("replacement").call() }; "Review author" to "review@example.invalid"
        })
        assertTrue(repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure)
        Git.open(root).use { assertEquals("replacement", it.repository.branch); assertEquals(before, it.repository.resolve("refs/heads/replacement")); assertEquals(before, it.repository.resolve("refs/heads/main")) }
        assertFalse(File(root, "incoming.md").exists())
    }
    @Test fun mediaThreeWayResultsKeepUnchangedIgnoredReplacementsForLocalAndRemoteMerges() = runBlocking {
        for (remote in listOf(false, true)) {
            val root = SampleRepository.openOrCreate(File(mediaWorkspace, "three-way-$remote")); roots += root.path
            Git.open(root).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                File(root, "retired.cache").writeText("Tracked base\n"); git.add().addFilepattern(".").call(); git.commit().setMessage("Base").call()
                git.push().setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call(); git.branchCreate().setName("incoming").call()
                git.rm().addFilepattern("retired.cache").call(); git.commit().setMessage("Ours deletion").call()
                git.checkout().setName("incoming").call()
                File(root, "incoming.md").writeText("Independent incoming\n"); git.add().addFilepattern(".").call(); git.commit().setMessage("Incoming").call()
                git.push().setRefSpecs(RefSpec("refs/heads/incoming:refs/heads/main")).call(); git.checkout().setName("main").call()
            }
            exclude(root, "retired.cache"); File(root, "retired.cache").writeText("Owned ignored replacement\n")
            if (remote) {
                val service = RepositoryRemoteMerge(); val plan = service.plan(root.path)
                assertEquals(RemoteMergeKind.MERGED, service.begin(plan, publish = false).kind)
            } else assertTrue(LocalBranchRepository().merge(root.path, "incoming", MergeStrategy.PREFER_FF).getOrThrow().success)
            assertEquals("Owned ignored replacement\n", File(root, "retired.cache").readText())
            Git.open(root).use { assertNull(it.repository.resolve("HEAD:retired.cache")); assertEquals(2, it.log().call().first().parentCount); assertTrue(it.status().call().isClean) }
        }
    }
}
