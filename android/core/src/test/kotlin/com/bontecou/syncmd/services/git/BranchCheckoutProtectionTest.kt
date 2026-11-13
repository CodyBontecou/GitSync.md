package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real Git refs, index and local contents; no account or live remote is involved. */
class BranchCheckoutProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(incomingPath: String = "private.cache"): File {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            git.checkout().setCreateBranch(true).setName("incoming").call()
            File(root, incomingPath).apply { parentFile.mkdirs(); writeText("Incoming branch contents\n") }
            git.add().addFilepattern(".").call(); git.commit().setMessage("Incoming file").call()
            git.checkout().setName("main").call()
        }
        return root
    }
    private fun exclude(root: File, pattern: String) { File(root, ".git/info/exclude").apply { parentFile.mkdirs(); appendText("\n$pattern\n") } }
    private suspend fun collision(shape: String, merge: Boolean) {
        val target = when (shape) { "directory" -> "cache"; "file-prefix" -> "cache/incoming.md"; else -> "private.cache" }
        val root = fixture(target)
        val private = if (shape == "directory") File(root, "cache/private.md") else File(root, if (shape == "file-prefix") "cache" else "private.cache")
        exclude(root, if (shape == "directory") "cache/" else private.name)
        private.parentFile.mkdirs(); private.writeText("Private local contents must survive\n")
        Git.open(root).use { git ->
            assertTrue(git.status().call().isClean)
            val head = git.repository.resolve("HEAD"); val index = File(root, ".git/index").readBytes()
            val config = File(root, ".git/config").readBytes(); val branches = LocalBranchRepository()
            val blocked = if (merge) branches.merge(root.path, "incoming", MergeStrategy.PREFER_FF).let { it.isFailure || !it.getOrThrow().success }
                else branches.switchBranch(root.path, "incoming").isFailure
            assertTrue("Must block $shape collision during ${if (merge) "merge" else "switch"}", blocked)
            assertEquals("main", git.repository.branch); assertEquals(head, git.repository.resolve("HEAD"))
            assertArrayEquals(index, File(root, ".git/index").readBytes()); assertArrayEquals(config, File(root, ".git/config").readBytes())
            assertEquals("Private local contents must survive\n", private.readText())
            assertFalse(File(root, ".git/MERGE_HEAD").exists())
        }
    }
    @Test fun `ignored file collision blocks branch switching`() = runBlocking { collision("file", false) }
    @Test fun `ignored file collision blocks branch merging`() = runBlocking { collision("file", true) }
    @Test fun `ignored directory contents survive branch switching`() = runBlocking { collision("directory", false) }
    @Test fun `ignored directory contents survive branch merging`() = runBlocking { collision("directory", true) }
    @Test fun `ignored file prefix survives checkout of an incoming directory`() = runBlocking { collision("file-prefix", false) }
    @Test fun `ignored file prefix survives merge of an incoming directory`() = runBlocking { collision("file-prefix", true) }
    @Test fun `unrelated ignored contents do not block a safe switch or merge`() = runBlocking {
        for (merge in listOf(false, true)) {
            val root = fixture("incoming.md"); exclude(root, "private.cache")
            File(root, "private.cache").writeText("Unrelated private contents\n")
            val branches = LocalBranchRepository()
            if (merge) assertTrue(branches.merge(root.path, "incoming", MergeStrategy.PREFER_FF).getOrThrow().success)
            else branches.switchBranch(root.path, "incoming").getOrThrow()
            assertEquals("Unrelated private contents\n", File(root, "private.cache").readText())
            assertEquals("Incoming branch contents\n", File(root, "incoming.md").readText())
        }
    }
    private fun deletedPathFixture(): File {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root, "retired.cache").writeText("Tracked base contents\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Base file").call()
            git.push().setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
            git.branchCreate().setName("incoming").call()
            git.rm().addFilepattern("retired.cache").call(); git.commit().setMessage("Delete locally").call()
            git.checkout().setName("incoming").call()
            File(root, "incoming.md").writeText("Independent incoming change\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Incoming independent file").call()
            git.push().setRefSpecs(RefSpec("refs/heads/incoming:refs/heads/main")).call()
            git.checkout().setName("main").call()
        }
        exclude(root, "retired.cache"); File(root, "retired.cache").writeText("Private replacement after deletion\n")
        return root
    }
    @Test fun `three way merge preserves an ignored replacement for an unchanged incoming deleted path`() = runBlocking {
        val root = deletedPathFixture()
        val result = LocalBranchRepository().merge(root.path, "incoming", MergeStrategy.PREFER_FF).getOrThrow()
        assertTrue(result.success); assertEquals(MergeType.MERGE_COMMIT, result.mergeType)
        assertEquals("Private replacement after deletion\n", File(root, "retired.cache").readText())
        Git.open(root).use { assertNull(it.repository.resolve("HEAD:retired.cache")); assertEquals(2, it.log().call().first().parentCount) }
    }
    @Test fun `reviewed remote merge allows an ignored replacement that its result does not checkout`() = runBlocking {
        val root = deletedPathFixture(); val service = RepositoryRemoteMerge()
        val plan = service.plan(root.path); val result = service.begin(plan, publish = false)
        assertEquals(RemoteMergeKind.MERGED, result.kind)
        assertEquals("Private replacement after deletion\n", File(root, "retired.cache").readText())
        Git.open(root).use { assertNull(it.repository.resolve("HEAD:retired.cache")); assertTrue(it.status().call().isClean) }
    }
    @Test fun `ignored collision created during author resolution cannot be overwritten`() = runBlocking {
        val root = fixture(); exclude(root, "private.cache"); var resolved = false
        val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val repository = LocalBranchRepository(authorResolver = {
            resolved = true; File(root, "private.cache").writeText("Late private contents\n"); "Review author" to "review@example.invalid"
        })
        val result = repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF)
        assertTrue(resolved); assertTrue(result.isFailure || !result.getOrThrow().success)
        assertEquals("Late private contents\n", File(root, "private.cache").readText())
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")) }
    }
    @Test fun `merge rejects tracked local edits before changing refs or index`() = runBlocking {
        val root = fixture("incoming.md"); File(root, "notes.md").appendText("Local unfinished draft\n")
        val before = Git.open(root).use { it.repository.resolve("HEAD") }; val index = File(root, ".git/index").readBytes()
        val result = LocalBranchRepository().merge(root.path, "incoming", MergeStrategy.PREFER_FF)
        assertTrue(result.isFailure || !result.getOrThrow().success)
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")) }; assertArrayEquals(index, File(root, ".git/index").readBytes())
        assertTrue(File(root, "notes.md").readText().endsWith("Local unfinished draft\n")); assertFalse(File(root, "incoming.md").exists())
    }
    @Test fun `merge rejects untracked local edits even when unrelated to incoming paths`() = runBlocking {
        val root = fixture("incoming.md"); File(root, "private.md").writeText("Untracked draft\n")
        val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val result = LocalBranchRepository().merge(root.path, "incoming", MergeStrategy.PREFER_FF)
        assertTrue(result.isFailure || !result.getOrThrow().success)
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")) }; assertEquals("Untracked draft\n", File(root, "private.md").readText())
    }
    @Test fun `branch switch during author resolution cannot merge into the replacement branch`() = runBlocking {
        val root = fixture("incoming.md"); val before = Git.open(root).use { it.branchCreate().setName("replacement").call(); it.repository.resolve("HEAD") }
        val repository = LocalBranchRepository(authorResolver = {
            Git.open(root).use { it.checkout().setName("replacement").call() }; "Review author" to "review@example.invalid"
        })
        assertTrue(repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure)
        Git.open(root).use { assertEquals("replacement", it.repository.branch); assertEquals(before, it.repository.resolve("refs/heads/replacement")); assertEquals(before, it.repository.resolve("refs/heads/main")) }
        assertFalse(File(root, "incoming.md").exists())
    }
    @Test fun `head change during author resolution is kept without applying the incoming merge`() = runBlocking {
        val root = fixture("incoming.md"); var changed: String? = null
        val repository = LocalBranchRepository(authorResolver = {
            changed = Git.open(root).use { it.commit().setAllowEmpty(true).setMessage("External local commit").call().name }; "Review author" to "review@example.invalid"
        })
        assertTrue(repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure)
        Git.open(root).use { assertEquals(changed, it.repository.resolve("HEAD").name) }; assertFalse(File(root, "incoming.md").exists())
    }
    @Test fun `source ref change during author resolution requires a new merge review`() = runBlocking {
        val root = fixture("incoming.md"); val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val repository = LocalBranchRepository(authorResolver = {
            Git.open(root).use { git -> git.repository.updateRef("refs/heads/incoming").apply { setNewObjectId(before); isForceUpdate = true }.forceUpdate() }
            "Review author" to "review@example.invalid"
        })
        assertTrue(repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure)
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")) }; assertFalse(File(root, "incoming.md").exists())
    }
    @Test fun `assume valid index changes during author resolution cannot hide edits from the guard`() = runBlocking {
        val root = fixture("notes.md"); val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val repository = LocalBranchRepository(authorResolver = {
            Git.open(root).use { git ->
                val cache = git.repository.lockDirCache()
                try { cache.getEntry("notes.md").isAssumeValid = true; cache.write(); assertTrue(cache.commit()) } finally { cache.unlock() }
                File(root, "notes.md").writeText("Hidden unfinished local edit\n")
                assertTrue(git.status().call().isClean)
            }
            "Review author" to "review@example.invalid"
        })
        assertTrue(repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure)
        assertEquals("Hidden unfinished local edit\n", File(root, "notes.md").readText())
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")); assertTrue(it.repository.readDirCache().getEntry("notes.md").isAssumeValid) }
    }
    @Test fun `configuration changes during author resolution are kept without merging`() = runBlocking {
        val root = fixture("incoming.md"); val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val repository = LocalBranchRepository(authorResolver = {
            Git.open(root).use { it.repository.config.apply { setBoolean("core", null, "autocrlf", true); save() } }
            "Review author" to "review@example.invalid"
        })
        assertTrue(repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure)
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")); assertTrue(it.repository.config.getBoolean("core", null, "autocrlf", false)) }
        assertFalse(File(root, "incoming.md").exists())
    }
    @Test fun `an identical replacement folder cannot inherit a pending merge`() = runBlocking {
        val root = fixture("incoming.md"); val moved = File(root.parentFile, "retained-original")
        val before = Git.open(root).use { it.repository.resolve("HEAD") }; val incoming = Git.open(root).use { it.repository.resolve("refs/heads/incoming") }
        val config = File(root, ".git/config").readBytes(); val index = File(root, ".git/index").readBytes()
        val repository = LocalBranchRepository(authorResolver = {
            assertTrue(root.renameTo(moved))
            Git.cloneRepository().setURI(moved.toURI().toString()).setDirectory(root).call().use { git ->
                git.branchCreate().setName("incoming").setStartPoint(incoming.name).call()
            }
            File(root, ".git/config").writeBytes(config); File(root, ".git/index").writeBytes(index)
            "Review author" to "review@example.invalid"
        })
        assertTrue(repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure)
        for (folder in listOf(root, moved)) Git.open(folder).use { assertEquals(before, it.repository.resolve("HEAD")); assertFalse(File(folder, "incoming.md").exists()) }
    }
    @Test fun `unrelated ignored data still permits persistent text conflicts`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            File(root, "conflict.md").writeText("base\n"); git.add().addFilepattern(".").call(); git.commit().setMessage("Base").call()
            git.checkout().setCreateBranch(true).setName("incoming").call()
            File(root, "conflict.md").writeText("incoming\n"); git.add().addFilepattern(".").call(); git.commit().setMessage("Incoming").call()
            git.checkout().setName("main").call()
            File(root, "conflict.md").writeText("ours\n"); git.add().addFilepattern(".").call(); git.commit().setMessage("Ours").call()
        }
        exclude(root, "private.cache"); File(root, "private.cache").writeText("Unrelated private contents\n")
        val result = LocalBranchRepository().merge(root.path, "incoming", MergeStrategy.PREFER_FF).getOrThrow()
        assertFalse(result.success); assertEquals(MergeType.CONFLICT, result.mergeType)
        Git.open(root).use { assertEquals(setOf("conflict.md"), it.status().call().conflicting); assertEquals(listOf(1, 2, 3), (0 until it.repository.readDirCache().entryCount).map { index -> it.repository.readDirCache().getEntry(index) }.filter { entry -> entry.pathString == "conflict.md" }.map { entry -> entry.stage }) }
        assertEquals("Unrelated private contents\n", File(root, "private.cache").readText())
    }
    @Test fun `an already up to date merge does not touch an ignored locally deleted path`() = runBlocking {
        val root = deletedPathFixture()
        Git.open(root).use { git ->
            // The base is an ancestor of ours and still contains the locally deleted path.
            git.branchCreate().setName("older").setStartPoint("main~1").call()
        }
        val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val result = LocalBranchRepository().merge(root.path, "older", MergeStrategy.PREFER_FF).getOrThrow()
        assertTrue(result.success); assertEquals(MergeType.UP_TO_DATE, result.mergeType)
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")) }; assertEquals("Private replacement after deletion\n", File(root, "retired.cache").readText())
    }
    @Test fun `fast forward only divergence does not perform a speculative checkout`() = runBlocking {
        val root = deletedPathFixture(); val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val result = LocalBranchRepository().merge(root.path, "incoming", MergeStrategy.FAST_FORWARD).getOrThrow()
        assertFalse(result.success)
        Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")) }; assertEquals("Private replacement after deletion\n", File(root, "retired.cache").readText()); assertFalse(File(root, "incoming.md").exists())
    }
    @Test fun `an active merge session is preserved instead of switching or merging again`() = runBlocking {
        for (switch in listOf(false, true)) {
            val root = fixture("incoming.md"); val before = Git.open(root).use { it.repository.resolve("HEAD") }
            File(root, ".git/MERGE_HEAD").writeText(before.name + "\n")
            val repository = LocalBranchRepository()
            val blocked = if (switch) repository.switchBranch(root.path, "incoming").isFailure else repository.merge(root.path, "incoming", MergeStrategy.PREFER_FF).isFailure
            assertTrue(blocked); assertEquals(before.name + "\n", File(root, ".git/MERGE_HEAD").readText()); assertFalse(File(root, "incoming.md").exists())
            Git.open(root).use { assertEquals(before, it.repository.resolve("HEAD")) }
        }
    }

}
