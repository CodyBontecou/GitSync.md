package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #149 (Android port of iOS #132): stop tracking one file, folder or glob. `git rm --cached`,
 * a root `.gitignore` entry and one commit holding only those two changes. Mirrors
 * `SyncMDTests/StopTrackingPathTests.swift` with real JGit repositories.
 */
class StopTrackingPathTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository = LocalUntrackRepository()

    private class Fixture(val root: File, val base: String)

    /** A repository with one commit holding [files] (path → contents). */
    private fun repo(files: Map<String, String>): Fixture {
        val root = temporary.newFolder()
        Git.init().setInitialBranch("main").setDirectory(root).call().use { git ->
            // Ignore files go in last: JGit's add skips paths they already ignore, and the fixtures track some.
            files.entries.sortedWith(compareBy({ it.key.endsWith(GitIgnoreFile.NAME) }, { it.key }))
                .forEach { (path, text) -> write(root, path, text); git.add().addFilepattern(path).call() }
            return Fixture(root, commit(git, "Base"))
        }
    }

    private fun commit(git: Git, message: String) =
        git.commit().setMessage(message).setAuthor("Tests", "tests@example.invalid").setCommitter("Tests", "tests@example.invalid").call().name

    private fun write(root: File, path: String, text: String) = File(root, path).apply { parentFile.mkdirs() }.writeText(text)
    private fun read(root: File, path: String) = File(root, path).readText()

    private fun stop(target: GitUntrackTarget, root: File): GitUntrackResult = runBlocking {
        repository.stopTracking(root.path, target, "Tests", "tests@example.invalid").getOrThrow()
    }

    private fun failure(target: GitUntrackTarget, root: File): Throwable = runBlocking {
        repository.stopTracking(root.path, target, "Tests", "tests@example.invalid").exceptionOrNull()!!
    }

    /** Blob text at `revision:path`, or null when the path isn't in that tree. */
    private fun blob(root: File, revision: String, path: String): String? = Git.open(root).use { git ->
        val repo = git.repository
        val commit = repo.resolve(revision) ?: return null
        RevWalk(repo).use { walk ->
            TreeWalk.forPath(repo, path, walk.parseCommit(commit).tree)?.use { String(repo.open(it.getObjectId(0)).bytes) }
        }
    }

    private fun indexHas(root: File, path: String) = Git.open(root).use { it.repository.readDirCache().findEntry(path) >= 0 }
    private fun head(root: File) = Git.open(root).use { it.repository.resolve(Constants.HEAD).name }
    private fun parent(root: File) = Git.open(root).use { git ->
        RevWalk(git.repository).use { it.parseCommit(git.repository.resolve(Constants.HEAD)).parents.singleOrNull()?.name }
    }
    private fun status(root: File) = Git.open(root).use { it.status().call() }
    private fun isIgnored(root: File, path: String) = Git.open(root).use { IgnoreRules(it.repository).isIgnored(path, false) }

    // ─── Entries and matching ────────────────────────────────────────────────

    @Test fun `ignore entries are root anchored and escaped`() {
        assertThat(GitUntrackTarget.File(".obsidian/workspace-mobile.json").ignoreEntry).isEqualTo("/.obsidian/workspace-mobile.json")
        assertThat(GitUntrackTarget.File("Daily Notes/My note.md").ignoreEntry).isEqualTo("/Daily Notes/My note.md")
        assertThat(GitUntrackTarget.File("a/[draft] *what?.md").ignoreEntry).isEqualTo("/a/\\[draft] \\*what\\?.md")
        assertThat(GitUntrackTarget.File("#notes/!x.md").ignoreEntry).isEqualTo("/#notes/!x.md")
        assertThat(GitUntrackTarget.File("trailing  ").ignoreEntry).isEqualTo("/trailing\\ \\ ")
        assertThat(GitUntrackTarget.Folder("/.obsidian/plugins/sync/").ignoreEntry).isEqualTo("/.obsidian/plugins/sync/")
        assertThat(GitUntrackTarget.ObsidianWorkspace.ignoreEntry).isEqualTo("/.obsidian/workspace*.json")
        assertThat(GitUntrackTarget.Folder("a/b").displayName).isEqualTo("a/b/")
        assertThat(GitUntrackTarget.File("a.md").commitMessage.lines().first()).isEqualTo("Stop tracking a.md")
        // Pulling the commit deletes the path on other devices; the message says so.
        assertThat(GitUntrackTarget.File("a.md").commitMessage).contains("deletes the copy on other devices")
        assertThat(GitUntrackTarget.Folder("a").commitMessage).contains("deletes the copy on other devices")
        assertThat(GitUntrackTarget.ObsidianWorkspace.commitMessage).contains("Obsidian recreates them")
        assertThat(GitUntrackTarget.ObsidianWorkspace.commitMessage).doesNotContain("deletes the copy")
    }

    @Test fun `matching`() {
        assertThat(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/workspace.json")).isTrue()
        assertThat(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/workspace-mobile.json")).isTrue()
        assertThat(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/workspaces.json.bak")).isFalse()
        assertThat(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/sub/workspace.json")).isFalse()
        assertThat(GitUntrackTarget.isObsidianWorkspaceFile("vault/.obsidian/workspace.json")).isFalse()
        assertThat(GitUntrackTarget.Folder("a b").matches("a b/c.md")).isTrue()
        assertThat(GitUntrackTarget.Folder("a").matches("ab/c.md")).isFalse()
        assertThat(GitUntrackTarget.Folder("a").matches("a")).isFalse()
        assertThat(GitUntrackTarget.File("a/b.md").matches("a/b.md")).isTrue()
        assertThat(GitUntrackTarget.File("a/b.md").matches("a/b.md.orig")).isFalse()
        assertThat(GitUntrackTarget.Pattern("notes/[ab]?.md").matches("notes/a1.md")).isTrue()
        assertThat(GitUntrackTarget.Pattern("notes/[!ab]?.md").matches("notes/a1.md")).isFalse()
        assertThat(GitUntrackTarget.Pattern("notes/\\*.md").matches("notes/*.md")).isTrue()
        assertThat(GitUntrackTarget.Pattern("notes/\\*.md").matches("notes/x.md")).isFalse()
    }

    @Test fun `options and default choice follow iOS`() {
        assertThat(GitUntrackTarget.options("notes/a.md")).containsExactly(GitUntrackTarget.File("notes/a.md"), GitUntrackTarget.Folder("notes")).inOrder()
        assertThat(GitUntrackTarget.options("a.md")).containsExactly(GitUntrackTarget.File("a.md"))
        assertThat(GitUntrackTarget.options(".obsidian/workspace.json")).containsExactly(GitUntrackTarget.File(".obsidian/workspace.json"),
            GitUntrackTarget.Folder(".obsidian"), GitUntrackTarget.ObsidianWorkspace).inOrder()
        assertThat(GitUntrackTarget.options(null)).containsExactly(GitUntrackTarget.ObsidianWorkspace)
        assertThat(GitUntrackTarget.defaultChoice(".obsidian/workspace-mobile.json")).isEqualTo(GitUntrackTarget.ObsidianWorkspace)
        assertThat(GitUntrackTarget.defaultChoice("notes/a.md")).isEqualTo(GitUntrackTarget.File("notes/a.md"))
        assertThat(GitUntrackTarget.defaultChoice(null)).isEqualTo(GitUntrackTarget.ObsidianWorkspace)
    }

    @Test fun `gitignore contains and append`() {
        val content = "# comment\n.DS_Store\n.obsidian/workspace.json  \r\n/a/\n"
        assertThat(GitIgnoreFile.contains("/.obsidian/workspace.json", content)).isTrue() // a/b and /a/b are the same rule
        assertThat(GitIgnoreFile.contains("/a/", content)).isTrue()
        assertThat(GitIgnoreFile.contains("/.DS_Store", content)).isFalse() // /x is anchored, x is not
        assertThat(GitIgnoreFile.contains("/trailing\\ ", "/trailing\n")).isFalse()
        assertThat(GitIgnoreFile.contains("/trailing\\ ", "/trailing\\ \r\n")).isTrue()
        assertThat(GitIgnoreFile.appending("/x", "")).isEqualTo("/x\n")
        assertThat(GitIgnoreFile.appending("/x", "a")).isEqualTo("a\n/x\n")
        assertThat(GitIgnoreFile.appending("/x", "a\n")).isEqualTo("a\n/x\n")
        assertThat(GitIgnoreFile.appending("/x", "a\r\n")).isEqualTo("a\r\n/x\r\n")
        assertThat(GitIgnoreFile.appending("/x", "a\r\nb")).isEqualTo("a\r\nb\r\n/x\r\n")
    }

    @Test fun `rejects paths outside the vault or inside Git`() {
        for (target in listOf(GitUntrackTarget.File(""), GitUntrackTarget.File("../x"), GitUntrackTarget.File("a/../b"),
                GitUntrackTarget.File(".git/config"), GitUntrackTarget.Folder(".git"), GitUntrackTarget.File(".gitignore"),
                GitUntrackTarget.File("a//b"), GitUntrackTarget.File("a\nb"), GitUntrackTarget.File("sub/.git/HEAD"))) {
            assertThrows(target.toString(), GitUntrackException.InvalidPath::class.java) { target.validate() }
        }
        GitUntrackTarget.File(".github/x.yml").validate()
        GitUntrackTarget.File(".gitattributes").validate()
    }

    // ─── Index, .gitignore and commit ────────────────────────────────────────

    @Test fun `stopping a file keeps it on disk, ignores it and commits`() {
        val fixture = repo(mapOf(".obsidian/workspace-mobile.json" to "{\"layout\":1}", "Note.md" to "note\n"))
        val root = fixture.root
        write(root, ".obsidian/workspace-mobile.json", "{\"layout\":2}")

        val result = stop(GitUntrackTarget.File(".obsidian/workspace-mobile.json"), root)

        assertThat(result.removedPaths).containsExactly(".obsidian/workspace-mobile.json")
        assertThat(result.addedIgnoreEntry).isEqualTo("/.obsidian/workspace-mobile.json")
        assertThat(read(root, ".obsidian/workspace-mobile.json")).isEqualTo("{\"layout\":2}") // edits included
        assertThat(read(root, ".gitignore")).isEqualTo("/.obsidian/workspace-mobile.json\n")
        assertThat(indexHas(root, ".obsidian/workspace-mobile.json")).isFalse()
        assertThat(head(root)).isEqualTo(result.commitSha)
        assertThat(parent(root)).isEqualTo(fixture.base)
        assertThat(blob(root, "HEAD", ".obsidian/workspace-mobile.json")).isNull()
        assertThat(blob(root, "HEAD", ".gitignore")).isEqualTo("/.obsidian/workspace-mobile.json\n")
        assertThat(blob(root, "HEAD", "Note.md")).isEqualTo("note\n")
        Git.open(root).use { git ->
            val commit = RevWalk(git.repository).use { it.parseCommit(git.repository.resolve("HEAD")) }
            assertThat(commit.shortMessage).isEqualTo("Stop tracking .obsidian/workspace-mobile.json")
            assertThat(commit.authorIdent.name).isEqualTo("Tests")
        }
        val status = status(root)
        assertThat(status.isClean).isTrue()
        assertThat(status.ignoredNotInIndex).contains(".obsidian/workspace-mobile.json")
    }

    @Test fun `appends to an existing gitignore without a final newline`() {
        val root = repo(mapOf(".gitignore" to ".DS_Store", "a.md" to "a")).root
        val result = stop(GitUntrackTarget.File("a.md"), root)
        assertThat(result.addedIgnoreEntry).isEqualTo("/a.md")
        assertThat(read(root, ".gitignore")).isEqualTo(".DS_Store\n/a.md\n")
        assertThat(blob(root, "HEAD", ".gitignore")).isEqualTo(".DS_Store\n/a.md\n")
        assertThat(status(root).isClean).isTrue()
    }

    @Test fun `CRLF gitignore stays CRLF`() {
        val root = repo(mapOf(".gitignore" to ".DS_Store\r\n", "a.md" to "a")).root
        stop(GitUntrackTarget.File("a.md"), root)
        assertThat(read(root, ".gitignore")).isEqualTo(".DS_Store\r\n/a.md\r\n")
        assertThat(blob(root, "HEAD", ".gitignore")).isEqualTo(".DS_Store\r\n/a.md\r\n")
    }

    /** The common desktop half-fix: the rule is in .gitignore but the file was never removed from the index. */
    @Test fun `already ignored file is untracked without duplicating the rule`() {
        val ignore = ".obsidian/workspace*.json\n"
        val root = repo(mapOf(".gitignore" to ignore, ".obsidian/workspace.json" to "{}", "n.md" to "n")).root
        assertThat(indexHas(root, ".obsidian/workspace.json")).isTrue()

        val result = stop(GitUntrackTarget.File(".obsidian/workspace.json"), root)

        assertThat(result.addedIgnoreEntry).isNull()
        assertThat(result.commitSha).isNotNull()
        assertThat(read(root, ".gitignore")).isEqualTo(ignore)
        assertThat(blob(root, "HEAD", ".obsidian/workspace.json")).isNull()
        assertThat(File(root, ".obsidian/workspace.json").exists()).isTrue()
    }

    @Test fun `a rule in a nested gitignore also counts as covered`() {
        val root = repo(mapOf("notes/.gitignore" to "draft.md\n", "notes/draft.md" to "d")).root
        val result = stop(GitUntrackTarget.File("notes/draft.md"), root)
        assertThat(result.addedIgnoreEntry).isNull()
        assertThat(File(root, ".gitignore").exists()).isFalse()
        assertThat(blob(root, "HEAD", "notes/draft.md")).isNull()
    }

    @Test fun `exact rule is not duplicated and a repeat is rejected`() {
        val root = repo(mapOf("a.md" to "a")).root
        stop(GitUntrackTarget.File("a.md"), root)
        val head = head(root)
        val error = failure(GitUntrackTarget.File("a.md"), root)
        assertThat(error).isInstanceOf(GitUntrackException.NotTracked::class.java)
        assertThat((error as GitUntrackException).displayName).isEqualTo("a.md")
        assertThat(read(root, ".gitignore")).isEqualTo("/a.md\n")
        assertThat(head(root)).isEqualTo(head)
    }

    @Test fun `nested path with spaces and glob characters`() {
        val path = "Projects/Q1 plan/notes [draft].md"
        val root = repo(mapOf(path to "draft", "Projects/Q1 plan/notes d.md" to "keep")).root

        val result = stop(GitUntrackTarget.File(path), root)

        assertThat(result.addedIgnoreEntry).isEqualTo("/Projects/Q1 plan/notes \\[draft].md")
        assertThat(isIgnored(root, path)).isTrue()
        assertThat(isIgnored(root, "Projects/Q1 plan/notes d.md")).isFalse() // the escaped rule must not act as a glob
        assertThat(blob(root, "HEAD", path)).isNull()
        assertThat(blob(root, "HEAD", "Projects/Q1 plan/notes d.md")).isEqualTo("keep")
        assertThat(status(root).isClean).isTrue()
    }

    @Test fun `folder removes every file below it`() {
        val root = repo(mapOf(
            ".obsidian/plugins/my plugin/data.json" to "{}",
            ".obsidian/plugins/my plugin/main.js" to "x",
            ".obsidian/plugins/other/data.json" to "{}",
        )).root
        val result = stop(GitUntrackTarget.Folder(".obsidian/plugins/my plugin"), root)
        assertThat(result.removedPaths).containsExactly(".obsidian/plugins/my plugin/data.json", ".obsidian/plugins/my plugin/main.js").inOrder()
        assertThat(result.addedIgnoreEntry).isEqualTo("/.obsidian/plugins/my plugin/")
        assertThat(blob(root, "HEAD", ".obsidian/plugins/other/data.json")).isNotNull()
        assertThat(blob(root, "HEAD", ".obsidian/plugins/my plugin/main.js")).isNull()
        assertThat(File(root, ".obsidian/plugins/my plugin/main.js").readText()).isEqualTo("x")
        assertThat(status(root).isClean).isTrue()
    }

    @Test fun `workspace pattern removes all workspace files`() = runBlocking {
        val root = repo(mapOf(".obsidian/workspace.json" to "{}", ".obsidian/workspace-mobile.json" to "{}", ".obsidian/app.json" to "{}")).root
        val tracked = repository.trackedPaths(root.path, GitUntrackTarget.ObsidianWorkspace).getOrThrow()
        assertThat(tracked).containsExactly(".obsidian/workspace-mobile.json", ".obsidian/workspace.json").inOrder()

        val result = stop(GitUntrackTarget.ObsidianWorkspace, root)

        assertThat(result.removedPaths).isEqualTo(tracked)
        assertThat(read(root, ".gitignore")).isEqualTo("/.obsidian/workspace*.json\n")
        assertThat(blob(root, "HEAD", ".obsidian/app.json")).isNotNull()
        assertThat(repository.trackedPaths(root.path, GitUntrackTarget.ObsidianWorkspace).getOrThrow()).isEmpty()
        assertThat(status(root).isClean).isTrue()
    }

    @Test fun `other staged changes stay staged and out of the commit`() {
        val root = repo(mapOf("Note.md" to "v1\n", "w.json" to "{}")).root
        write(root, "Note.md", "v2\n")
        write(root, "New.md", "new\n")
        Git.open(root).use { it.add().addFilepattern("Note.md").addFilepattern("New.md").call() }

        stop(GitUntrackTarget.File("w.json"), root)

        assertThat(blob(root, "HEAD", "Note.md")).isEqualTo("v1\n") // the staged edit is not committed
        assertThat(blob(root, "HEAD", "New.md")).isNull()
        val status = status(root)
        assertThat(status.changed).containsExactly("Note.md") // the edit stays staged
        assertThat(status.added).containsExactly("New.md")
        assertThat(status.modified).isEmpty()
        assertThat(status.untracked).isEmpty()
    }

    @Test fun `uncommitted gitignore rules go along with the commit`() {
        val root = repo(mapOf(".gitignore" to "*.tmp\n", "a.md" to "a")).root
        write(root, ".gitignore", "*.tmp\n*.bak\n")
        stop(GitUntrackTarget.File("a.md"), root)
        assertThat(blob(root, "HEAD", ".gitignore")).isEqualTo("*.tmp\n*.bak\n/a.md\n")
        assertThat(status(root).isClean).isTrue()
    }

    @Test fun `staged-only file is unstaged and ignored`() {
        val fixture = repo(mapOf("a.md" to "a"))
        val root = fixture.root
        write(root, "new.md", "new")
        Git.open(root).use { it.add().addFilepattern("new.md").call() }

        val result = stop(GitUntrackTarget.File("new.md"), root)

        assertThat(result.removedPaths).containsExactly("new.md")
        assertThat(indexHas(root, "new.md")).isFalse()
        assertThat(parent(root)).isEqualTo(fixture.base) // the commit adds the .gitignore rule
        assertThat(read(root, "new.md")).isEqualTo("new")
        assertThat(status(root).isClean).isTrue()
    }

    @Test fun `staged deletion of a tracked file is committed as the untrack`() {
        val root = repo(mapOf("a.md" to "a", "b.md" to "b")).root
        Git.open(root).use { it.rm().addFilepattern("a.md").call() }
        val result = stop(GitUntrackTarget.File("a.md"), root)
        assertThat(result.removedPaths).containsExactly("a.md")
        assertThat(blob(root, "HEAD", "a.md")).isNull()
        assertThat(status(root).isClean).isTrue()
    }

    @Test fun `untracked ignored path is rejected`() {
        val root = repo(mapOf(".gitignore" to "*.tmp\n", "a.md" to "a")).root
        val head = head(root)
        val error = failure(GitUntrackTarget.File("x.tmp"), root)
        assertThat(error).isInstanceOf(GitUntrackException.NotTracked::class.java)
        assertThat(head(root)).isEqualTo(head)
        assertThat(read(root, ".gitignore")).isEqualTo("*.tmp\n")
    }

    @Test fun `invalid path is rejected before touching the repository`() {
        val root = repo(mapOf("a.md" to "a")).root
        assertThat(failure(GitUntrackTarget.File("../a.md"), root)).isInstanceOf(GitUntrackException.InvalidPath::class.java)
        assertThat(File(root, ".gitignore").exists()).isFalse()
    }

    @Test fun `conflicts block the operation and leave everything as it was`() {
        val root = repo(mapOf("a.md" to "base\n", "w.json" to "{}")).root
        Git.open(root).use { git ->
            git.checkout().setCreateBranch(true).setName("other").call()
            write(root, "a.md", "other\n"); git.add().addFilepattern("a.md").call(); commit(git, "Other")
            git.checkout().setName("main").call()
            write(root, "a.md", "main\n"); git.add().addFilepattern("a.md").call(); commit(git, "Main")
            git.merge().include(git.repository.resolve("other")).call()
        }
        val head = head(root)
        val error = failure(GitUntrackTarget.File("w.json"), root)
        // JGit leaves a merge with conflicts in the MERGING state, which is checked first.
        assertThat(error).isInstanceOf(GitUntrackException.OperationInProgress::class.java)
        assertThat(head(root)).isEqualTo(head)
        assertThat(File(root, ".gitignore").exists()).isFalse()
        assertThat(indexHas(root, "w.json")).isTrue()
        assertThat(File(root, ".git/index.lock").exists()).isFalse()
    }

    @Test fun `unborn branch gets a first commit with the rule`() {
        val root = temporary.newFolder()
        Git.init().setInitialBranch("main").setDirectory(root).call().use { git ->
            write(root, "a.md", "a"); write(root, "b.md", "b")
            git.add().addFilepattern("a.md").addFilepattern("b.md").call()
        }
        val result = stop(GitUntrackTarget.File("a.md"), root)
        assertThat(result.commitSha).isNotNull()
        assertThat(blob(root, "HEAD", ".gitignore")).isEqualTo("/a.md\n")
        assertThat(blob(root, "HEAD", "b.md")).isNull() // staged, not part of the stop-tracking commit
        assertThat(status(root).added).containsExactly("b.md")
    }

    /** Round trip with #137: the receiving device pulls the commit and keeps its local edit as an ignored file. */
    @Test fun `another device pulls the stop-tracking commit cleanly and keeps its copy`() = runBlocking {
        val workspace = ".obsidian/workspace-mobile.json"
        val remote = temporary.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        val phone = temporary.newFolder("phone")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(phone).call().use { git ->
            write(phone, "a.md", "note\n"); write(phone, workspace, "{\"layout\":\"phone\"}\n")
            git.add().addFilepattern(".").call(); commit(git, "Base")
            git.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
        val tablet = temporary.newFolder("tablet")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(tablet).setBranch("main").call().close()

        stop(GitUntrackTarget.ObsidianWorkspace, phone)
        Git.open(phone).use { it.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call() }

        write(tablet, workspace, "{\"layout\":\"tablet\"}\n")
        val pull = LocalPullRepository().executePull(tablet.path).getOrThrow()
        assertThat(pull.success).isTrue()
        assertThat(head(tablet)).isEqualTo(head(phone))
        assertThat(read(tablet, workspace)).isEqualTo("{\"layout\":\"tablet\"}\n")
        assertThat(status(tablet).isClean).isTrue()
        assertThat(status(tablet).ignoredNotInIndex).contains(workspace)
    }
}
