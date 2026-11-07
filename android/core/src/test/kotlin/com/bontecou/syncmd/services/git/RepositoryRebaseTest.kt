package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryRebaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(conflict: Boolean = false): File {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { local ->
            local.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call()
            local.push().call()
            File(root, if (conflict) "notes.md" else "local.md").writeText("Local commit\n")
            local.add().addFilepattern(".").call(); local.commit().setMessage("Local work").call()
        }
        Git.cloneRepository().setURI(File(root.parentFile, "sample-remote.git").toURI().toString()).setDirectory(temporary.newFolder()).call().use { peer ->
            peer.repository.config.apply { setString("user", null, "name", "Remote Author"); setString("user", null, "email", "remote@example.invalid"); save() }
            File(peer.repository.workTree, if (conflict) "notes.md" else "remote.md").writeText("Remote commit\n")
            peer.add().addFilepattern(".").call(); peer.commit().setMessage("Remote work").call(); peer.push().call()
        }
        return root
    }
    private suspend fun assertProblemPreservesRepository(root: File, problem: RebaseProblem) {
        val metadata = listOf("HEAD", "index", "config").associateWith { File(root,".git/$it").readBytes() }
        val work = root.walkTopDown().filter { it.isFile && ".git" !in it.relativeTo(root).invariantSeparatorsPath.split('/') }
            .associate { it.relativeTo(root).path to it.readBytes() }
        val remote = Git.open(File(root.parentFile,"sample-remote.git")).use { it.repository.resolve("HEAD") }
        var authCalls = 0
        val service = RepositoryRebase(credentials = { _, _ -> authCalls++; error("Blocked planning must not authenticate") })
        val failure = runCatching { service.plan(root.path) }.exceptionOrNull()
        assertTrue("Expected typed recovery: $failure",failure is RebaseFailure)
        assertEquals(problem,(failure as RebaseFailure).problem);assertEquals(0,authCalls)
        metadata.forEach { (name,bytes) -> assertArrayEquals(name,bytes,File(root,".git/$name").readBytes()) }
        work.forEach { (name,bytes) -> assertArrayEquals(name,bytes,File(root,name).readBytes()) }
        assertEquals(remote,Git.open(File(root.parentFile,"sample-remote.git")).use { it.repository.resolve("HEAD") })
    }
    @Test fun `dirty planning and newly dirty reviewed plans retain typed recovery and all local work`() = runBlocking {
        val root=fixture();val service=RepositoryRebase();val plan=service.plan(root.path)
        File(root,"notes.md").appendText("Uncommitted user draft 日本語\n")
        assertProblemPreservesRepository(root,RebaseProblem.LOCAL_CHANGES)
        val before=File(root,"notes.md").readBytes();val index=File(root,".git/index").readBytes()
        val failure=runCatching { service.begin(plan) }.exceptionOrNull()
        assertEquals(RebaseProblem.LOCAL_CHANGES,(failure as RebaseFailure).problem)
        assertArrayEquals(before,File(root,"notes.md").readBytes());assertArrayEquals(index,File(root,".git/index").readBytes())
    }
    @Test fun `detached planning identifies local branch recovery without fetching`() = runBlocking {
        val root=fixture();Git.open(root).use { it.checkout().setName(it.repository.resolve("HEAD").name).call() }
        assertProblemPreservesRepository(root,RebaseProblem.LOCAL_BRANCH_REQUIRED)
    }
    @Test fun `missing upstream remote or branch and local upstream share typed connection recovery`() = runBlocking {
        for (mode in listOf("remote","merge","local")) {
            val root=fixture();Git.open(root).use { git -> git.repository.config.apply {
                when(mode) { "remote" -> unset("branch","main","remote");"merge" -> unset("branch","main","merge");else -> setString("branch","main","remote",".") };save()
            } }
            assertProblemPreservesRepository(root,RebaseProblem.UPSTREAM_REQUIRED)
        }
    }
    @Test fun `active conflict planning identifies operation recovery and preserves the paused session`() = runBlocking {
        val root=fixture(true);val service=RepositoryRebase();val paused=service.begin(service.plan(root.path)).session!!
        assertProblemPreservesRepository(root,RebaseProblem.ACTIVE_OPERATION)
        assertEquals(paused,service.session(root.path))
    }
    @Test fun `clean rebase replays local commits and never pushes`() = runBlocking {
        val root = fixture(); val service = RepositoryRebase(author = { "Rebase Author" to "rebase@example.invalid" })
        val plan = service.plan(root.path); assertEquals(1, plan.ahead); assertEquals(1, plan.behind)
        val remoteBefore = Git.open(File(root.parentFile, "sample-remote.git")).use { it.repository.resolve("HEAD") }
        val result = service.begin(plan); assertNull(result.session)
        Git.open(root).use { git ->
            assertEquals("main", git.repository.branch); assertTrue(git.status().call().isClean)
            val head = git.log().setMaxCount(1).call().single()
            assertNotEquals(plan.head, head.name); assertEquals(plan.upstreamHead, head.getParent(0).name)
            assertEquals("Sample Author", head.authorIdent.name); assertEquals("Rebase Author", head.committerIdent.name)
        }
        assertEquals("Local commit\n", File(root, "local.md").readText()); assertEquals("Remote commit\n", File(root, "remote.md").readText())
        assertEquals(remoteBefore, Git.open(File(root.parentFile, "sample-remote.git")).use { it.repository.resolve("HEAD") })
    }
    @Test fun `conflict resumes from git metadata in a new service after staged manual resolution`() = runBlocking {
        val root = fixture(true); val service = RepositoryRebase(); val plan = service.plan(root.path)
        val stopped = service.begin(plan).session!!
        assertEquals("Remote commit\n", stopped.conflicts.single().currentContent)
        assertEquals("Local commit\n", stopped.conflicts.single().incomingContent)
        assertTrue(runCatching { service.continueRebase(stopped) }.isFailure)
        LocalConflictRepository().resolveConflict(root.path, "notes.md", ConflictResolutionStrategy.MANUAL, "Combined resolution\n").getOrThrow()
        assertTrue(runCatching { service.continueRebase(stopped) }.isFailure)
        val restarted = RepositoryRebase(); val refreshed = restarted.session(root.path)!!
        assertTrue(refreshed.conflicts.isEmpty()); assertNull(restarted.continueRebase(refreshed).session)
        assertEquals("Combined resolution\n", File(root, "notes.md").readText())
        assertEquals(RepositoryState.SAFE, Git.open(root).use { it.repository.repositoryState })
    }
    @Test fun `abort returns exact original head and working contents without contacting remote`() = runBlocking {
        val root = fixture(true); val service = RepositoryRebase(); val plan = service.plan(root.path)
        service.begin(plan)
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", "/unavailable-rebase-fixture"); save() } }
        val restarted = RepositoryRebase(); val session = restarted.session(root.path)!!
        assertNull(restarted.abort(session).session)
        assertEquals(plan.head, Git.open(root).use { it.repository.resolve("HEAD").name })
        assertEquals("Local commit\n", File(root, "notes.md").readText())
    }
    @Test fun `dirty tree branch changes and changed upstream invalidate review before rewriting`() = runBlocking {
        val root = fixture(); val service = RepositoryRebase(); val plan = service.plan(root.path)
        File(root, "draft.md").writeText("Keep dirty work")
        assertTrue(runCatching { service.begin(plan) }.isFailure); assertTrue(runCatching { service.plan(root.path) }.isFailure)
        File(root, "draft.md").delete()
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("other").call() }
        assertTrue(runCatching { service.begin(plan) }.isFailure)
        Git.open(root).use { it.checkout().setName("main").call() }
        Git.open(File(root.parentFile, "sample-remote.git")).use { remote ->
            // A peer changing the reviewed upstream must force another review, never silently rebase onto it.
            val ref = remote.repository.updateRef("refs/heads/main"); ref.setNewObjectId(remote.repository.resolve("HEAD^")); ref.setForceUpdate(true)
            assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.FORCED, ref.update())
        }
        assertTrue(runCatching { service.begin(plan) }.isFailure)
        assertEquals(plan.head, Git.open(root).use { it.repository.resolve("HEAD").name })
        assertNull(service.session(root.path))
    }
    @Test fun `stale abort cannot discard a later conflict edit`() = runBlocking {
        val root = fixture(true); val service = RepositoryRebase(); val stopped = service.begin(service.plan(root.path)).session!!
        File(root, "notes.md").writeText("Later resolution draft\n")
        assertTrue(runCatching { service.abort(stopped) }.isFailure)
        assertEquals("Later resolution draft\n", File(root, "notes.md").readText())
        assertNotNull(service.session(root.path))
    }
    @Test fun `merge topology survives replay`() = runBlocking {
        val root = fixture()
        Git.open(root).use { git ->
            val base = git.repository.resolve("HEAD^")
            git.checkout().setCreateBranch(true).setName("topic").setStartPoint(base.name).call()
            File(root, "topic.md").writeText("Topic work\n"); git.add().addFilepattern(".").call(); val topic = git.commit().setMessage("Topic work").call()
            git.checkout().setName("main").call(); assertTrue(git.merge().include(topic).call().mergeStatus.isSuccessful)
        }
        val service = RepositoryRebase(); val plan = service.plan(root.path)
        assertNull(service.begin(plan).session)
        Git.open(root).use { git ->
            val head = git.log().setMaxCount(1).call().single(); assertEquals(2, head.parentCount)
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk -> assertTrue(walk.isMergedInto(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(plan.upstreamHead)), walk.parseCommit(head))) }
        }
        assertEquals("Topic work\n", File(root, "topic.md").readText())
    }
    @Test fun `changed original branch is preserved even after refreshing the paused session`() = runBlocking {
        val root = fixture(true); val service = RepositoryRebase(); val plan = service.plan(root.path); service.begin(plan)
        Git.open(root).use { git -> val ref = git.repository.updateRef(plan.branch); ref.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(plan.upstreamHead)); ref.setForceUpdate(true); ref.update() }
        val refreshed = service.session(root.path)!!
        assertTrue(runCatching { service.abort(refreshed) }.isFailure)
        assertEquals(plan.upstreamHead, Git.open(root).use { it.repository.exactRef(plan.branch).objectId.name })
    }
    @Test fun `empty resolution requires explicit skip instead of silently losing the local commit`() = runBlocking {
        val root = fixture(true); val service = RepositoryRebase(); val plan = service.plan(root.path); service.begin(plan)
        LocalConflictRepository().resolveConflict(root.path, "notes.md", ConflictResolutionStrategy.OURS).getOrThrow()
        val next = service.continueRebase(service.session(root.path)!!)
        assertEquals("NOTHING_TO_COMMIT", next.status); assertNotNull(next.session)
        assertNull(service.skip(service.session(root.path)!!).session)
        assertEquals(plan.upstreamHead, Git.open(root).use { it.repository.resolve("HEAD").name })
    }
    @Test fun `delete modify conflict can accept an absent upstream side`() = runBlocking {
        val root = fixture(true)
        Git.cloneRepository().setURI(File(root.parentFile, "sample-remote.git").toURI().toString()).setDirectory(temporary.newFolder()).call().use { peer ->
            peer.repository.config.apply { setString("user", null, "name", "Remote Author"); setString("user", null, "email", "remote@example.invalid"); save() }
            peer.rm().addFilepattern("notes.md").call(); peer.commit().setMessage("Delete remote note").call(); peer.push().call()
        }
        val service = RepositoryRebase(); service.begin(service.plan(root.path))
        assertEquals(1, service.session(root.path)!!.conflicts.size)
        assertFalse(service.session(root.path)!!.conflicts.single().hasCurrentVersion)
        assertTrue(service.session(root.path)!!.conflicts.single().hasIncomingVersion)
        LocalConflictRepository().resolveConflict(root.path, "notes.md", ConflictResolutionStrategy.OURS).getOrThrow()
        assertFalse(File(root, "notes.md").exists()); assertTrue(service.session(root.path)!!.conflicts.isEmpty())
        val continued = service.continueRebase(service.session(root.path)!!)
        if (continued.session != null) assertNull(service.skip(service.session(root.path)!!).session)
        assertFalse(File(root, "notes.md").exists())
    }
    @Test fun `changed account policy invalidates a reviewed rebase`() = runBlocking {
        val root = fixture(); var policy = "original-account"
        val service = RepositoryRebase(credentialRevision = { policy }); val plan = service.plan(root.path)
        policy = "replacement-account"
        assertTrue(runCatching { service.begin(plan) }.isFailure)
        assertEquals(plan.head, Git.open(root).use { it.repository.resolve("HEAD").name }); assertNull(service.session(root.path))
    }
    @Test fun `deleted remote branch cannot rebase using a stale tracking ref`() = runBlocking {
        val root = fixture(); val service = RepositoryRebase(); val plan = service.plan(root.path)
        Git.open(File(root.parentFile, "sample-remote.git")).use { git -> val ref = git.repository.updateRef("refs/heads/main"); ref.setForceUpdate(true); ref.delete() }
        assertTrue(runCatching { service.plan(root.path) }.isFailure)
        assertTrue(runCatching { service.begin(plan) }.isFailure)
        assertEquals(plan.head, Git.open(root).use { it.repository.resolve("HEAD").name }); assertNull(service.session(root.path))
    }
    @Test fun `clean rebase disables implicit stashing without rewriting user configuration`() = runBlocking {
        val root = fixture()
        Git.open(root).use { it.repository.config.apply { setBoolean("rebase", null, "autoStash", true); save() } }
        val config = File(root, ".git/config").readBytes()
        val service = RepositoryRebase(); assertNull(service.begin(service.plan(root.path)).session)
        assertArrayEquals(config, File(root, ".git/config").readBytes())
        assertTrue(Git.open(root).use { it.stashList().call().isEmpty() })
    }
    @Test fun `abort never overwrites a new untracked file at an original tracked path`() = runBlocking {
        val root = fixture(true)
        Git.open(File(root.parentFile, "sample-remote.git")).use { remote ->
            val peer = Git.cloneRepository().setURI(remote.repository.directory.toURI().toString()).setDirectory(temporary.newFolder()).call()
            peer.use { git ->
                git.repository.config.apply { setString("user", null, "name", "Remote"); setString("user", null, "email", "remote@example.invalid"); save() }
                git.rm().addFilepattern("notes.md").call(); git.commit().setMessage("Delete upstream note").call(); git.push().call()
            }
        }
        val service = RepositoryRebase(); service.begin(service.plan(root.path))
        LocalConflictRepository().resolveConflict(root.path, "notes.md", ConflictResolutionStrategy.OURS).getOrThrow()
        File(root, "notes.md").writeText("New untracked note\n")
        // Ignored files still contain user data and must receive the same protection.
        File(root, ".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\nnotes.md\n")
        val failure = runCatching { service.abort(service.session(root.path)!!) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Untracked files"))
        assertEquals("New untracked note\n", File(root, "notes.md").readText()); assertNotNull(service.session(root.path))
    }
    @Test fun `abort preserves unrelated untracked files`() = runBlocking {
        val root = fixture(true); val service = RepositoryRebase(); service.begin(service.plan(root.path))
        File(root, "spare-untracked.md").writeText("Keep this unrelated draft\n")
        File(root, ".git/info/exclude").apply { parentFile.mkdirs() }.appendText("\nspare-untracked.md\n")
        assertNull(service.abort(service.session(root.path)!!).session)
        assertEquals("Keep this unrelated draft\n", File(root, "spare-untracked.md").readText())
    }
}
