package com.bontecou.syncmd

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** Invoke the two phases in different debug-app processes with the same rebaseFixture UUID. */
class RebaseColdStartTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = InstrumentationRegistry.getArguments().getString("rebaseFixture")
        ?.also { require(it.matches(Regex("[a-f0-9-]{36}"))) } ?: error("Pass a fresh rebaseFixture UUID")
    private val workspace get() = File(app.filesDir, "rebase-cold-$fixture").canonicalFile
    @Test fun preparePausedRebase() = runBlocking<Unit> {
        require(!workspace.exists()) { "Use a fresh fixture UUID" }
        val root = SampleRepository.openOrCreate(File(workspace, "source")).canonicalFile
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call(); git.push().call()
            File(root, "notes.md").writeText("Local before restart\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Local cold-start work").call()
        }
        val remote = File(root.parentFile, "sample-remote.git")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(File(workspace, "peer")).call().use { peer ->
            peer.repository.config.apply { setString("user", null, "name", "Remote fixture"); setString("user", null, "email", "remote@example.invalid"); save() }
            File(peer.repository.workTree, "notes.md").writeText("Remote before restart\n")
            peer.add().addFilepattern(".").call(); peer.commit().setMessage("Remote cold-start work").call(); peer.push().call()
        }
        val service = RepositoryRebase(); val plan = service.plan(root.path)
        assertNotNull(service.begin(plan).session)
        LocalConflictRepository().resolveConflict(root.path, "notes.md", ConflictResolutionStrategy.MANUAL, "Resumed 日本語 🚀\n").getOrThrow()
        assertTrue(service.session(root.path)!!.conflicts.isEmpty())
        // Continuation must remain offline even after an actual process restart.
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", "/missing-cold-rebase-remote"); save() } }
        val handoff = JSONObject().put("pid", Process.myPid()).put("root", root.path).put("remote", remote.path)
            .put("upstream", plan.upstreamHead).put("original", plan.head)
        FileOutputStream(File(workspace, "handoff.json")).use { it.write(handoff.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }
    @Test fun continueInFreshProcess() = runBlocking<Unit> {
        val handoff = JSONObject(File(workspace, "handoff.json").readText())
        assertNotEquals("Use a new debug-app process", handoff.getInt("pid"), Process.myPid())
        val root = File(handoff.getString("root")); require(root.canonicalPath.startsWith(workspace.path + "/"))
        val service = RepositoryRebase(); val session = service.session(root.path)!!
        assertTrue(session.conflicts.isEmpty())
        assertEquals(handoff.getString("original"), session.originalHead)
        assertNull(service.continueRebase(session).session)
        assertEquals("Resumed 日本語 🚀\n", File(root, "notes.md").readText())
        Git.open(root).use { git ->
            assertEquals("main", git.repository.branch); assertTrue(git.status().call().isClean)
            val head = git.log().setMaxCount(1).call().single()
            assertEquals(handoff.getString("upstream"), head.getParent(0).name)
        }
        assertEquals(handoff.getString("upstream"), Git.open(File(handoff.getString("remote"))).use { it.repository.resolve("HEAD").name })
        workspace.deleteRecursively()
    }
}
