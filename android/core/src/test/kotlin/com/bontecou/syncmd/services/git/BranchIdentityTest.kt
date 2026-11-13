package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.BranchType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BranchIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `branch reviews expose full object identity for current local and remote refs`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val branches = LocalBranchRepository()
        Git.open(root).use { git ->
            val current = branches.getCurrentBranch(root.path).getOrThrow()
            assertEquals(git.repository.resolve("HEAD").name, current.lastCommit)
            for (branch in branches.listBranches(root.path).getOrThrow()) {
                val ref = if (branch.type == BranchType.REMOTE) "refs/remotes/origin/${branch.name}" else "refs/heads/${branch.name}"
                assertEquals(git.repository.exactRef(ref).objectId.name, branch.lastCommit)
            }
        }
    }
    @Test fun `detached current branch retains its full head identity`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            val head = git.repository.resolve("HEAD").name
            git.checkout().setName(head).call()
            val current = LocalBranchRepository().getCurrentBranch(root.path).getOrThrow()
            assertEquals(head, current.name); assertEquals(head, current.lastCommit)
        }
    }
}
