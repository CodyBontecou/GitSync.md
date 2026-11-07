package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryRemoteMergeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(conflict: Boolean = false, local: Boolean = true): File {
        val path = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(path).use { it.reset().setMode(ResetCommand.ResetType.HARD).call(); it.push().call() }
        if (local) Git.open(path).use { git ->
            File(path, if (conflict) "notes.md" else "local.md").writeText("Local contents\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("Local commit").call()
        }
        advance(path, if (conflict) "notes.md" else "incoming.md")
        return path
    }
    private fun remote(path: File) = File(path.parentFile, "sample-remote.git")
    private fun head(path: File) = Git.open(path).use { it.repository.resolve("HEAD").name }
    private fun advance(path: File, name: String = "later.md") {
        Git.cloneRepository().setURI(remote(path).toURI().toString()).setDirectory(temporary.newFolder()).call().use { peer ->
            File(peer.repository.workTree, name).writeText("Incoming contents\n")
            peer.add().addFilepattern(".").call()
            peer.commit().setMessage("Incoming").setAuthor("Peer", "peer@example.invalid").setCommitter("Peer", "peer@example.invalid").call()
            peer.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
    }
    private fun service() = RepositoryRemoteMerge(authorResolver = { "Reviewed Author" to "reviewed@example.invalid" })

    @Test fun `review has no checkout side effects and confirmed local merge preserves two parents and author`() = runBlocking {
        val path = fixture(); val original = head(path); val remote = head(remote(path))
        val config = File(path, ".git/config").readBytes(); val index = File(path, ".git/index").readBytes()
        val service = service(); val plan = service.plan(path.path)
        assertThat(plan.ahead).isEqualTo(1); assertThat(plan.behind).isEqualTo(1)
        assertThat(head(path)).isEqualTo(original); assertThat(File(path, ".git/index").readBytes()).isEqualTo(index)
        val result = service.begin(plan, false)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.MERGED); assertThat(result.push).isEqualTo(RemoteMergePush.NOT_REQUESTED)
        Git.open(path).use { git ->
            val merged = git.log().setMaxCount(1).call().single()
            assertThat(merged.parents.map { it.name }).containsExactly(original, remote).inOrder()
            assertThat(merged.authorIdent.name).isEqualTo("Reviewed Author")
        }
        assertThat(File(path, ".git/config").readBytes()).isEqualTo(config)
        assertThat(head(remote(path))).isEqualTo(remote)
        assertThat(File(path, "local.md").readText()).isEqualTo("Local contents\n")
        assertThat(File(path, "incoming.md").readText()).isEqualTo("Incoming contents\n")
        assertThat(runCatching { service.begin(plan, true) }.isFailure).isTrue()
    }
    @Test fun `merge and push publishes the exact result without publishing tags`() = runBlocking {
        val path = fixture(); Git.open(path).use { it.tag().setName("private-draft").call() }
        val service = service(); val result = service.begin(service.plan(path.path), true)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.MERGED)
        assertThat(result.push).isEqualTo(RemoteMergePush.CONFIRMED)
        assertThat(head(remote(path))).isEqualTo(result.head)
        Git.open(remote(path)).use { assertThat(it.repository.exactRef("refs/tags/private-draft")).isNull() }
    }
    @Test fun `conflicts are persistent and never pushed then existing conflict completion resolves them`() = runBlocking {
        val path = fixture(true); val original = head(path); val beforeRemote = head(remote(path))
        val service = service(); val result = service.begin(service.plan(path.path), true)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.CONFLICTS)
        assertThat(result.push).isEqualTo(RemoteMergePush.BLOCKED_BY_CONFLICTS)
        assertThat(service().hasConflicts(path.path)).isTrue()
        assertThat(head(path)).isEqualTo(original); assertThat(head(remote(path))).isEqualTo(beforeRemote)
        val conflicts = LocalConflictRepository({ "Resolver" to "resolver@example.invalid" })
        val conflict = conflicts.getConflicts(path.path).getOrThrow().single()
        conflicts.resolveConflict(path.path, conflict.filePath, ConflictResolutionStrategy.MANUAL, "Combined contents\n").getOrThrow()
        conflicts.completeMerge(path.path, "Resolve reviewed merge").getOrThrow()
        assertThat(service.hasConflicts(path.path)).isFalse()
        assertThat(File(path, "notes.md").readText()).isEqualTo("Combined contents\n")
        assertThat(head(remote(path))).isEqualTo(beforeRemote)
    }
    @Test fun `changed remote after review is rejected without changing head index or local files`() = runBlocking {
        val path = fixture(); val service = service(); val plan = service.plan(path.path)
        val original = head(path); val index = File(path, ".git/index").readBytes()
        advance(path)
        assertThat(runCatching { service.begin(plan, false) }.isFailure).isTrue()
        assertThat(head(path)).isEqualTo(original); assertThat(File(path, ".git/index").readBytes()).isEqualTo(index)
        assertThat(File(path, "incoming.md").exists()).isFalse()
    }
    @Test fun `head config credentials and author are revalidated after review`() = runBlocking {
        for (change in listOf("head", "config", "credentials", "author")) {
            val path = fixture(); var policy = "original"; var name = "Original Author"
            val service = RepositoryRemoteMerge(credentialRevision = { policy }, authorResolver = { name to "author@example.invalid" })
            val plan = service.plan(path.path)
            when (change) {
                "head" -> Git.open(path).use { it.commit().setAllowEmpty(true).setMessage("External commit").call() }
                "config" -> Git.open(path).use { it.repository.config.apply { setBoolean("pull", null, "rebase", true); save() } }
                "credentials" -> policy = "replaced"
                "author" -> name = "Replaced Author"
            }
            val original = head(path)
            assertThat(runCatching { service.begin(plan, true) }.isFailure).isTrue()
            assertThat(head(path)).isEqualTo(original)
            assertThat(File(path, "incoming.md").exists()).isFalse()
        }
    }
    @Test fun `unconfirmed push keeps merge and explicit retry publishes the same commit`() = runBlocking {
        val path = fixture(); val destination = File(path.parentFile, "later-created.git")
        Git.open(path).use { it.repository.config.apply { setString("remote", "origin", "pushurl", destination.toURI().toString()); save() } }
        val sourceBefore = head(remote(path)); val service = service(); val plan = service.plan(path.path)
        assertThat(plan.fetchAddress).isNotEqualTo(plan.pushAddress)
        val result = service.begin(plan, true)
        assertThat(result.push).isEqualTo(RemoteMergePush.UNCONFIRMED)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.MERGED)
        assertThat(head(path)).isEqualTo(result.head); assertThat(head(remote(path))).isEqualTo(sourceBefore)
        Git.cloneRepository().setURI(remote(path).toURI().toString()).setBare(true).setDirectory(destination).call().close()
        val retried = service.retryPush(plan, result)
        assertThat(retried.push).isEqualTo(RemoteMergePush.CONFIRMED)
        assertThat(head(path)).isEqualTo(result.head); assertThat(head(destination)).isEqualTo(result.head)
    }
    @Test fun `fast forward needs no author and local only never publishes`() = runBlocking {
        val path = fixture(local = false); val original = head(path); val incoming = head(remote(path))
        val service = RepositoryRemoteMerge(authorResolver = { error("Fast-forward needs no commit identity") })
        val plan = service.plan(path.path); assertThat(plan.author).isNull()
        assertThat(head(path)).isEqualTo(original)
        val result = service.begin(plan, false)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.FAST_FORWARD)
        assertThat(result.push).isEqualTo(RemoteMergePush.NOT_REQUESTED)
        assertThat(head(path)).isEqualTo(incoming)
    }
    @Test fun `unconfirmed retry rejects a later local commit instead of publishing it`() = runBlocking {
        val path = fixture()
        Git.open(path).use { it.repository.config.apply { setString("remote", "origin", "pushurl", File(path.parentFile, "missing.git").toURI().toString()); save() } }
        val service = service(); val plan = service.plan(path.path); val result = service.begin(plan, true)
        assertThat(result.push).isEqualTo(RemoteMergePush.UNCONFIRMED)
        Git.open(path).use { it.commit().setAllowEmpty(true).setMessage("Later local commit").call() }
        val later = head(path)
        assertThat(runCatching { service.retryPush(plan, result) }.isFailure).isTrue()
        assertThat(head(path)).isEqualTo(later)
    }
    @Test fun `local changes during author resolution cannot enter the reviewed merge`() = runBlocking {
        val path = fixture(); var resolutions = 0
        val service = RepositoryRemoteMerge(authorResolver = {
            resolutions++
            if (resolutions == 2) File(path, "README.md").writeText("External editor contents\n")
            "Reviewed Author" to "reviewed@example.invalid"
        })
        val plan = service.plan(path.path); val original = head(path)
        assertThat(runCatching { service.begin(plan, false) }.isFailure).isTrue()
        assertThat(head(path)).isEqualTo(original)
        assertThat(File(path, "README.md").readText()).isEqualTo("External editor contents\n")
        assertThat(File(path, "incoming.md").exists()).isFalse()
    }
    @Test fun `already ahead branch can explicitly publish without another merge or commit`() = runBlocking {
        val path = fixture(); val service = service(); service.begin(service.plan(path.path), false)
        val original = head(path); val plan = service.plan(path.path)
        assertThat(plan.behind).isEqualTo(0); assertThat(plan.ahead).isGreaterThan(0)
        val result = service.begin(plan, true)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.UP_TO_DATE)
        assertThat(result.push).isEqualTo(RemoteMergePush.CONFIRMED)
        assertThat(head(path)).isEqualTo(original); assertThat(head(remote(path))).isEqualTo(original)
    }
    @Test fun `credential change while preparing push keeps merge without publishing`() = runBlocking {
        val path = fixture(); var revision = "initial"; var resolutions = 0
        val service = RepositoryRemoteMerge(authorResolver = { "Reviewed Author" to "reviewed@example.invalid" },
            credentialRevision = { revision }, credentials = { _, _ ->
                resolutions++; if (resolutions == 3) revision = "changed"
                com.bontecou.syncmd.data.models.Credentials.None
            })
        val original = head(path); val remote = head(remote(path))
        val result = service.begin(service.plan(path.path), true)
        assertThat(result.kind).isEqualTo(RemoteMergeKind.MERGED)
        assertThat(result.push).isEqualTo(RemoteMergePush.UNCONFIRMED)
        assertThat(head(path)).isNotEqualTo(original); assertThat(head(remote(path))).isEqualTo(remote)
        assertThat(head(path)).isEqualTo(result.head)
    }
}
