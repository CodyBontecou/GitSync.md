package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SampleRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `sample supports real offline diff commit push and pull`() {
        val workspace = temporary.newFolder("workspace")
        val working = SampleRepository.openOrCreate(workspace)
        Git.open(working).use { git ->
            assertThat(git.log().call().count()).isEqualTo(2)
            assertThat(git.status().call().modified).contains("notes.md")
            assertThat(git.branchList().call().map { it.name }).contains("refs/heads/sample-branch")
            assertThat(git.repository.config.getString("remote", "origin", "url")).startsWith("file:")
            assertThat(git.diff().call()).isNotEmpty()
            git.add().addFilepattern("notes.md").call()
            val commit = git.commit().setMessage("Practice commit").call()
            val updates = git.push().setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
                .flatMap { it.remoteUpdates }
            assertThat(updates.map { it.status }).contains(RemoteRefUpdate.Status.OK)
            git.pull().call()
            Git.open(File(workspace, "sample-remote.git")).use { remote ->
                assertThat(remote.repository.resolve("refs/heads/main")).isEqualTo(commit.id)
            }
            assertThat(git.status().call().isClean).isTrue()
        }
    }

    @Test fun `reopening preserves user practice edits`() {
        val workspace = temporary.newFolder("workspace")
        val working = SampleRepository.openOrCreate(workspace)
        val notes = File(working, "notes.md")
        notes.writeText("Keep my changes")
        assertThat(SampleRepository.openOrCreate(workspace)).isEqualTo(working)
        assertThat(notes.readText()).isEqualTo("Keep my changes")
    }

    @Test fun `occupied workspace is never overwritten`() {
        val workspace = temporary.newFolder("occupied")
        val original = File(workspace, "important.md").apply { writeText("Keep") }
        val result = runCatching { SampleRepository.openOrCreate(workspace) }
        assertThat(result.isFailure).isTrue()
        assertThat(original.readText()).isEqualTo("Keep")
    }

    @Test fun `removed sample can be restored from its on-device remote`() {
        val workspace = temporary.newFolder("workspace")
        SampleRepository.openOrCreate(workspace).deleteRecursively()
        val restored = SampleRepository.openOrCreate(workspace)
        Git.open(restored).use { git ->
            assertThat(git.repository.branch).isEqualTo("main")
            assertThat(git.log().call().count()).isEqualTo(1)
            assertThat(git.repository.config.getString("user", null, "name")).isEqualTo("Sample Author")
        }
    }
}
