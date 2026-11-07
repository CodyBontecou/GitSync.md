package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.automation.*
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Run each phase in a separate process with the same callbackFixture UUID.
 * The handoff is an isolated test artifact, not a production token store.
 */
class RepositoryCallbackColdStartTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): String {
        val value = InstrumentationRegistry.getArguments().getString("callbackFixture")
        assumeNotNull(value)
        return requireNotNull(value).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    }
    private fun workspace(id: String) = File(app.filesDir, "callback-cold-$id").canonicalFile
    private fun host(id: String) = object : ContextWrapper(app) {
        override fun getFilesDir() = workspace(id)
        override fun getNoBackupFilesDir() = File(workspace(id), "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("callback-cold-$id-$name", mode)
    }
    private fun backend(context: Context): RepositoryCallbackRunner {
        val catalog = PullAllCatalog(context)
        return RepositoryCallbackRunner(RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(),
            { "Cold fixture" to "cold@example.invalid" }, catalog::requireCurrent,
            credentialRevision = RepositoryCredentialStore(context)::recordVersion), catalog::snapshot, { "fixture-owner" })
    }
    private fun head(folder: File) = Git.open(folder).use { it.repository.resolve("HEAD").name }

    @Test fun preparePersistedAuthorization() = runBlocking<Unit> {
        val id = fixture(); val folder = workspace(id)
        require(!folder.exists()) { "Use a fresh callbackFixture UUID" }; check(folder.mkdir())
        val context = host(id); val root = File(folder, "Notes"); val bare = File(folder, "remote.git")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "note.md").writeText("Initial\n"); git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid")
                .setCommitter("Fixture", "fixture@example.invalid").call()
            git.repository.config.apply {
                setString("remote", "origin", "url", bare.toURI().toString())
                setStringList("remote", "origin", "fetch", listOf("+refs/heads/*:refs/remotes/origin/*"))
                setString("branch", "main", "remote", "origin"); setString("branch", "main", "merge", "refs/heads/main"); save()
            }
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        }
        assertTrue(context.getSharedPreferences("sync_md_prefs", 0).edit().putInt("repository_count", 1)
            .putString("repo_0_name", "Cold fixture").putString("repo_0_path", root.path).putLong("repo_0_date", 19).commit())
        val runner = backend(context); val store = RepositoryCallbackGrantStore(context)
        val request = RepositoryCallbackProtocol.parse("syncmd://x-callback-url/push?repo=Notes&message=Cold%20authorized%20commit")
        val issued = RepositoryCallbackGrantService(runner, store).issue(runner.review(request, PullAllCatalog(context).snapshot().single()), true)
        val token = RepositoryCallbackGrantStore.tokenFromLink(issued.link)
        assertFalse(context.getSharedPreferences("repository_callback_grants", 0).all.values.single().toString().contains(token))
        File(root, "note.md").writeText("Draft created before process death\n")
        File(folder, "handoff.json").writeText(JSONObject().put("pid", Process.myPid()).put("token", token)
            .put("grant", issued.grant.scope.id).put("head", head(root)).toString())
        assertEquals(head(root), head(bare))
    }

    @Test fun executeInFreshProcessAndPersistRevocation() = runBlocking<Unit> {
        val id = fixture(); val folder = workspace(id); val data = JSONObject(File(folder, "handoff.json").readText())
        assertNotEquals(data.getInt("pid"), Process.myPid())
        val root = File(folder, "Notes"); val bare = File(folder, "remote.git"); val context = host(id)
        // Starting the app must not replay the persisted authorization or commit the draft.
        assertEquals(data.getString("head"), head(root)); assertEquals(head(root), head(bare))
        assertEquals("Draft created before process death\n", File(root, "note.md").readText())
        val store = RepositoryCallbackGrantStore(context); val token = data.getString("token")
        val grant = store.authorized(token)
        assertEquals(data.getString("grant"), grant.scope.id)
        val result = RepositoryCallbackGrantService(backend(context), store).run(token, grant) {}
        assertEquals(RepositoryActionOutcome.PUSHED, result.outcome)
        assertNotEquals(data.getString("head"), head(root)); assertEquals(head(root), head(bare))
        Git.open(root).use { assertEquals("Cold authorized commit", it.log().call().first().fullMessage) }
        store.revoke(grant.scope.id)
        File(root, "note.md").writeText("Draft after revocation\n")
        File(folder, "handoff.json").writeText(data.put("pid", Process.myPid()).put("head", head(root)).toString())
    }

    @Test fun revokedLinkRemainsDisabledInAnotherFreshProcess() = runBlocking<Unit> {
        val id = fixture(); val folder = workspace(id); val data = JSONObject(File(folder, "handoff.json").readText())
        assertNotEquals(data.getInt("pid"), Process.myPid())
        val root = File(folder, "Notes"); val bare = File(folder, "remote.git"); val context = host(id)
        val store = RepositoryCallbackGrantStore(context); val token = data.getString("token")
        val revoked = store.records().single(); assertFalse(revoked.scope.enabled)
        val service = RepositoryCallbackGrantService(backend(context), store)
        assertFalse(service.canReturn(token, revoked))
        var prepared = false
        assertTrue(runCatching { service.run(token, revoked) { prepared = true } }.isFailure)
        assertFalse(prepared); assertEquals(data.getString("head"), head(root)); assertEquals(head(root), head(bare))
        assertEquals("Draft after revocation\n", File(root, "note.md").readText())
        store.forget(revoked.scope.id); assertTrue(store.records().isEmpty())
        assertTrue(runCatching { store.authorized(token) }.isFailure)
        listOf("sync_md_prefs", "repository_callback_grants", "repository_shortcuts", "repository_credentials").forEach {
            assertTrue(app.deleteSharedPreferences("callback-cold-$id-$it"))
        }
        assertTrue(folder.deleteRecursively())
    }
}
