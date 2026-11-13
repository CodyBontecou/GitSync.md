package com.bontecou.syncmd.services.publication

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class FolderPublicationGitTest {
    @get:Rule val temporary=TemporaryFolder()
    private val service=FolderPublicationGit(seconds={1700000000})
    private fun root()=temporary.newFolder().canonicalFile
    private fun input(review:FolderPublicationReview,selected:Set<String> = review.suggestedPaths) = FolderPublicationInput(UUID.randomUUID().toString(),review,selected,"Fixture Author","fixture@example.invalid","Reviewed initial commit\n\nBody preserved.\n")
    @Test fun `inspection leaves no metadata and suggests only eligible nonsensitive nonignored files`() = runBlocking {
        val root=root();File(root,"readme.md").writeText("Reviewed\r\n")
        File(root,".env").writeText("FAKE_SECRET=fixture-only");File(root,".env.local").writeText("Fake local")
        File(root,"identity.pem").writeText("Fake key")
        File(root,".gitignore").writeText("ignored/\n*.tmp\n")
        File(root,"ignored").mkdir();File(root,"ignored/inside.md").writeText("Ignored")
        File(root,"other.tmp").writeText("Ignored too")
        val review=service.inspect(root.path)
        assertThat(review.suggestedPaths).containsExactly("readme.md",".gitignore")
        assertThat(review.files.filter { it.ignored }.map { it.path }).containsExactly("ignored/inside.md","other.tmp")
        assertThat(File(root,".git").exists()).isFalse()
        assertThat(root.list()!!.toSet()).containsExactly("readme.md",".env",".env.local","identity.pem",".gitignore","ignored","other.tmp")
        Unit
    }
    @Test fun `preparation commits exact reviewed bytes mode and empty files while exclusions survive stage all`() = runBlocking {
        val root=root();File(root,"read me 名.md").writeBytes("Reviewed\r\nbytes\n".toByteArray())
        File(root,"empty").writeBytes(byteArrayOf());File(root,"run.sh").writeText("#!/bin/sh\nexit 0\n");File(root,"run.sh").setExecutable(true,true)
        File(root,".env").writeText("FAKE_SECRET=excluded")
        File(root,"ignored [name]*!.txt").writeText("Excluded literal path")
        File(root,"ignored x.txt").writeText("Selected sibling")
        val review=service.inspect(root.path);val selected=review.suggestedPaths-"ignored [name]*!.txt";val request=input(review,selected)
        val before=review.files.associate { it.path to File(root,it.path).readBytes().toList() }
        val prepared=service.prepare(request)
        assertThat(service.prepare(request)).isEqualTo(prepared)
        assertThat(service.validatePrepared(root.path,request.workflow,prepared.commit)).isEqualTo(prepared)
        assertThat(review.files.associate { it.path to File(root,it.path).readBytes().toList() }).isEqualTo(before)
        Git.open(root).use { git ->
            RevWalk(git.repository).use { walk ->
                val commit=walk.parseCommit(git.repository.resolve("HEAD"));assertThat(commit.parentCount).isEqualTo(0)
                assertThat(commit.fullMessage).isEqualTo(request.message);assertThat(commit.authorIdent.emailAddress).isEqualTo(request.authorEmail)
                val seen=mutableSetOf<String>()
                TreeWalk(git.repository).use { tree ->tree.addTree(commit.tree);tree.isRecursive=true;while(tree.next()) {
                    seen+=tree.pathString
                    assertThat(git.repository.open(tree.getObjectId(0)).bytes.toList()).isEqualTo(before.getValue(tree.pathString))
                    if(tree.pathString=="run.sh")assertThat(tree.getFileMode(0)).isEqualTo(FileMode.EXECUTABLE_FILE)
                } }
                assertThat(seen).containsExactlyElementsIn(selected)
            }
            git.add().addFilepattern(".").call()
            assertThat(git.repository.readDirCache().getEntry(".env")).isNull()
            assertThat(git.repository.readDirCache().getEntry("ignored [name]*!.txt")).isNull()
            assertThat(git.repository.readDirCache().getEntry("ignored x.txt")).isNotNull()
            assertThat(git.status().call().isClean).isTrue()
        }
    }
    @Test fun `changes to unselected files new files ignored rules and executable bits invalidate review before metadata`() = runBlocking {
        for(kind in listOf("unselected","new","ignore","mode")) {
            val root=root();File(root,"selected.md").writeText("Selected");val other=File(root,"other.md").apply { writeText("Other") }
            val request=input(service.inspect(root.path),setOf("selected.md"))
            when(kind) {
                "unselected" -> other.writeText("Changed")
                "new" -> File(root,"new.md").writeText("New")
                "ignore" -> File(root,".gitignore").writeText("selected.md\n")
                "mode" -> File(root,"selected.md").setExecutable(true,true)
            }
            assertThat(runCatching { service.prepare(request) }.exceptionOrNull()).isInstanceOf(FolderPublicationChanged::class.java)
            assertThat(File(root,".git").exists()).isFalse()
        }
    }
    @Test fun `existing broken enclosing nested and symlink metadata are preserved`() = runBlocking {
        val existing=root();File(existing,"file.md").writeText("Existing");Git.init().setDirectory(existing).call().close()
        val config=File(existing,".git/config").readBytes()
        assertThat(runCatching { service.inspect(existing.path) }.isFailure).isTrue()
        assertThat(File(existing,".git/config").readBytes()).isEqualTo(config)
        val enclosing=root();File(enclosing,".git").writeText("gitdir: missing\n");val child=File(enclosing,"child").apply { mkdir() };File(child,"file.md").writeText("Child")
        assertThat(runCatching { service.inspect(child.path) }.isFailure).isTrue();assertThat(File(child,".git").exists()).isFalse()
        val nested=root();File(nested,"inside").mkdir();File(nested,"inside/.git").writeText("Broken metadata")
        assertThat(runCatching { service.inspect(nested.path) }.isFailure).isTrue();assertThat(File(nested,"inside/.git").readText()).isEqualTo("Broken metadata")
        val linked=root();val target=File(temporary.root,"outside").apply { writeText("Outside") };Files.createSymbolicLink(File(linked,"link").toPath(),target.toPath())
        assertThat(runCatching { service.inspect(linked.path) }.isFailure).isTrue();assertThat(target.readText()).isEqualTo("Outside")
    }
    @Test fun `oversized conversion and pointer files cannot enter the initial commit`() = runBlocking {
        val root=root();File(root,".gitattributes").writeText("*.dat filter=lfs\n*.txt text\n*.raw working-tree-encoding=UTF-16\n")
        listOf("asset.dat","converted.txt","encoded.raw").forEach { File(root,it).writeText("Fixture") }
        val large=File(root,"large.bin");java.io.RandomAccessFile(large,"rw").use { it.setLength(FolderPublicationLimits.FILE_BYTES+1) }
        File(root,"pointer.md").writeText("version https://git-lfs.github.com/spec/v1\noid sha256:${"a".repeat(64)}\nsize 12\n")
        val review=service.inspect(root.path)
        assertThat(review.files.filter { it.path in setOf("asset.dat","converted.txt","encoded.raw") }.all { !it.eligible }).isTrue()
        assertThat(review.suggestedPaths).doesNotContain("large.bin")
        for(path in listOf("asset.dat","converted.txt","encoded.raw","large.bin","pointer.md")) {
            assertThat(runCatching { service.prepare(input(review,setOf(path))) }.isFailure).isTrue()
        }
        assertThat(File(root,"pointer.md").readText()).contains("oid sha256:")
    }
    @Test fun `insufficient storage does not create metadata`() = runBlocking {
        val root=root();File(root,"file.md").writeText("Fixture");val request=input(service.inspect(root.path))
        val limited=FolderPublicationGit(capacity={0},seconds={1700000000})
        assertThat(runCatching { limited.prepare(request) }.isFailure).isTrue()
        assertThat(File(root,".git").exists()).isFalse();assertThat(File(root,"file.md").readText()).isEqualTo("Fixture")
    }
    @Test fun `different workflow and foreign history never replace prepared metadata`() = runBlocking {
        val root=root();File(root,"file.md").writeText("Fixture");val request=input(service.inspect(root.path));val prepared=service.prepare(request)
        val marker=File(root,".git/folder-publication.json").readBytes()
        assertThat(runCatching { service.prepare(request.copy(workflow=UUID.randomUUID().toString())) }.exceptionOrNull()).isInstanceOf(FolderPublicationOwnership::class.java)
        File(root,"file.md").writeText("Newer work")
        Git.open(root).use { git ->git.add().addFilepattern("file.md").call();git.commit().setMessage("Foreign newer commit").call() }
        val head=Git.open(root).use { it.repository.resolve("HEAD") };val index=File(root,".git/index").readBytes()
        assertThat(runCatching { service.validatePrepared(root.path,request.workflow,prepared.commit) }.isFailure).isTrue()
        assertThat(runCatching { service.prepare(request) }.isFailure).isTrue()
        assertThat(Git.open(root).use { it.repository.resolve("HEAD") }).isEqualTo(head)
        assertThat(File(root,".git/index").readBytes()).isEqualTo(index);assertThat(File(root,".git/folder-publication.json").readBytes()).isEqualTo(marker)
    }
    @Test fun `recovery recognizes only the deterministic commit and preserves newer staged work`() = runBlocking {
        val root=root();val file=File(root,"file.md").apply { writeText("Reviewed old bytes") };val request=input(service.inspect(root.path));val prepared=service.prepare(request)
        val marker=File(root,".git/folder-publication.json")
        val json=JsonParser.parseString(marker.readText()).asJsonObject.apply { remove("prepared") };marker.writeText(json.toString())
        file.writeText("Newer user edit")
        Git.open(root).use { it.add().addFilepattern("file.md").call() }
        val index=File(root,".git/index").readBytes()
        assertThat(FolderPublicationGit(seconds={1800000000}).prepare(request)).isEqualTo(prepared)
        assertThat(service.validatePrepared(root.path,request.workflow,prepared.commit)).isEqualTo(prepared)
        assertThat(File(root,".git/index").readBytes()).isEqualTo(index);assertThat(file.readText()).isEqualTo("Newer user edit")
        Git.open(root).use { git ->RevWalk(git.repository).use { walk ->
            val commit=walk.parseCommit(git.repository.resolve("HEAD"));TreeWalk.forPath(git.repository,"file.md",commit.tree).use { tree ->
                assertThat(String(git.repository.open(tree.getObjectId(0)).bytes)).isEqualTo("Reviewed old bytes")
            }
        } }
    }
    @Test fun `owned partial preparation resumes after an index lock without rewriting the commit`() = runBlocking {
        val root=root();File(root,"file.md").writeText("Fixture");val request=input(service.inspect(root.path));val prepared=service.prepare(request)
        val marker=File(root,".git/folder-publication.json");marker.writeText(JsonParser.parseString(marker.readText()).asJsonObject.apply { remove("prepared") }.toString())
        check(File(root,".git/refs/heads/main").delete())
        val lock=File(root,".git/index.lock").apply { writeText("External client lock") };val index=File(root,".git/index").readBytes()
        assertThat(runCatching { service.prepare(request) }.isFailure).isTrue();assertThat(lock.readText()).isEqualTo("External client lock")
        assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
        lock.delete()
        assertThat(service.prepare(request)).isEqualTo(prepared)
    }
    @Test fun `corrupt marker and altered saved blobs are not adopted`() = runBlocking {
        val root=root();File(root,"file.md").writeText("Fixture");val request=input(service.inspect(root.path));val prepared=service.prepare(request)
        val marker=File(root,".git/folder-publication.json");val original=marker.readText();marker.writeText("{broken")
        assertThat(runCatching { service.validatePrepared(root.path,request.workflow,prepared.commit) }.exceptionOrNull()).isInstanceOf(FolderPublicationOwnership::class.java)
        assertThat(marker.readText()).isEqualTo("{broken")
        marker.writeText(original.replace("Fixture Author","Different Author"))
        assertThat(runCatching { service.validatePrepared(root.path,request.workflow,prepared.commit) }.exceptionOrNull()).isInstanceOf(FolderPublicationOwnership::class.java)
    }
    @Test fun `ordinary bare lookalike names remain normal content and size estimates are bounded`() = runBlocking {
        val root=root();File(root,"HEAD").writeText("Ordinary document");File(root,"config").writeText("Ordinary config");File(root,"objects").mkdir();File(root,"refs").mkdir()
        assertThat(service.inspect(root.path).suggestedPaths).containsExactly("HEAD","config")
        val example=FolderPublicationFile("file.md",100,"a".repeat(64),false,false)
        assertThat(FolderPublicationLimits.estimate(listOf(example)).additionalBytes).isEqualTo(100L*3+4096+16L*1024*1024)
        assertThat(runCatching { FolderPublicationLimits.estimate(List(10001) { example }) }.isFailure).isTrue()
        assertThat(runCatching { FolderPublicationLimits.estimate(List(27) { example.copy(size=10L*1024*1024) }) }.isFailure).isTrue()
    }
}
