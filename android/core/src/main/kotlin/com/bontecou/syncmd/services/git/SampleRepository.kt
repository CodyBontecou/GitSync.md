package com.bontecou.syncmd.services.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import java.io.File

/** A real Git workspace with an on-device remote; no account or network is needed. */
object SampleRepository {
    fun openOrCreate(workspace: File): File {
        val working = File(workspace, "sample-repository")
        if (File(working, ".git").isDirectory) {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(working).use { it.repository.resolve("HEAD") ?: error("Sample repository has no commits") }
            return working
        }
        val remote = File(workspace, "sample-remote.git")
        if (!working.exists() && File(remote, "HEAD").isFile) {
            Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(working).call().close()
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(working).use { git ->
                git.repository.config.apply {
                    setString("user", null, "name", "Sample Author")
                    setString("user", null, "email", "sample@example.invalid")
                    save()
                }
            }
            return working
        }
        require(!workspace.exists() || workspace.listFiles()?.isEmpty() == true) {
            "Sample workspace is incomplete. Remove its files before creating another sample."
        }
        check(workspace.mkdirs() || workspace.isDirectory)
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close()
        Git.init().setDirectory(working).setInitialBranch("main").call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Sample Author")
                setString("user", null, "email", "sample@example.invalid")
                setString("remote", "origin", "url", remote.toURI().toString())
                setStringList("remote", "origin", "fetch", listOf("+refs/heads/*:refs/remotes/origin/*"))
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
                save()
            }
            File(working, "README.md").writeText("""
                # Sample repository

                This workspace uses real Git operations with an on-device remote.
                Review diffs, stage files, commit, switch branches, and pull or push offline.
                No GitHub account or credentials are used. Your own repositories are separate.
            """.trimIndent() + "\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Create sample workspace").call()
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
            git.fetch().setRemote("origin").call()
            git.branchCreate().setName("sample-branch").call()
            File(working, "notes.md").writeText("# Notes\n\n- Review a diff\n- Make a commit\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Add a practice checklist").call()
            File(working, "notes.md").appendText("- Push to the on-device remote\n")
        }
        return working
    }
}
