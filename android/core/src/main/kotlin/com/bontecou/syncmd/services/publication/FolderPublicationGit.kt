package com.bontecou.syncmd.services.publication

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.ScopedCredentialsProvider
import com.bontecou.syncmd.services.git.HttpsRemote
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.lfs.LfsPointer
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import com.google.gson.Gson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.internal.storage.file.LockFile
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.LfsFactory
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Date
import java.util.TimeZone
import java.util.UUID

interface FolderPublicationGitOperations {
    suspend fun inspect(path: String): FolderPublicationReview
    suspend fun prepare(input: FolderPublicationInput): FolderPreparedCommit
    suspend fun validatePrepared(path: String, workflow: String, expectedCommit: String): FolderPreparedCommit
    suspend fun publish(path: String, workflow: String, expectedCommit: String, remoteUrl: String, credentials: Credentials)
    suspend fun finish(path: String, workflow: String, expectedCommit: String, remoteUrl: String, credentials: Credentials)
    suspend fun clearPending(path: String, workflow: String, expectedCommit: String, remoteUrl: String)
}

/** First-publication Git state. No working file is written and no ordinary staging/filter command is run. */
class FolderPublicationGit(private val capacity: (File) -> Long = { it.usableSpace }, private val seconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val transport: (Repository,String) -> Transport = { repository,url -> Transport.open(repository,URIish(url)) }) : FolderPublicationGitOperations {
    private data class Marker(val schema: Int, val input: FolderPublicationInput, val timestamp: Long, val prepared: FolderPreparedCommit? = null, val remote: String? = null)
    private val gson = Gson()
    override suspend fun inspect(path: String): FolderPublicationReview = RepositoryOperationCoordinator.withRepository(path) {
        val root = FolderPublicationFiles.validateRoot(path)
        if (FolderPublicationFiles.exists(File(root,".git"))) throw FolderPublicationUnsupported("This folder already has Git metadata. Import the existing repository instead.")
        FolderPublicationFiles.inspect(root)
    }
    override suspend fun prepare(input: FolderPublicationInput): FolderPreparedCommit = withRepository(input.review.path, input.workflow) {
        val operation = currentCoroutineContext()
        val author = RepositoryIdentity.validated(input.authorName,input.authorEmail)
        val normalized = input.copy(authorName=author.first,authorEmail=author.second,selected=input.selected.toSortedSet())
        require(UUID.fromString(normalized.workflow).toString() == normalized.workflow && normalized.message.isNotBlank() && normalized.message.length <= 65536 && '\u0000' !in normalized.message)
        val files = normalized.review.files
        require(files.map { it.path }.distinct().size == files.size && files.size <= FolderPublicationLimits.INSPECTED_FILES)
        files.forEach { FolderPublicationFiles.path(it.path);require(it.digest.matches(Regex("[a-f0-9]{64}")) && it.size >= 0) }
        require(normalized.selected.isNotEmpty() && normalized.selected.all { path -> files.any { it.path == path && it.eligible } }) { "Select eligible files from this review" }
        val estimate=FolderPublicationLimits.estimate(files.filter { it.path in normalized.selected })
        val root=FolderPublicationFiles.validateRoot(normalized.review.path)
        val gitdir=File(root,".git")
        var marker = if (FolderPublicationFiles.exists(gitdir)) readMarker(root,normalized.workflow).also {
            if (it.input.copy(review=it.input.review.copy(path=normalized.review.path)) != normalized) throw FolderPublicationOwnership()
        } else Marker(1,normalized,seconds())
        if (FolderPublicationFiles.exists(gitdir) && File(gitdir,"HEAD").isFile && File(gitdir,"objects").isDirectory) {
            open(root).use { repository ->
                val head=repository.resolve("HEAD")
                if (head != null) {
                    val saved=validateCommit(repository,marker,head.name)
                    if (marker.prepared != null && marker.prepared != saved) throw FolderPublicationOwnership()
                    if (marker.prepared == null) writeMarker(root,marker.copy(prepared=saved),marker)
                    return@withRepository saved
                }
            }
        }
        if (FolderPublicationFiles.identity(root) != normalized.review.identity || FolderPublicationFiles.inspect(root,FolderPublicationFiles.exists(gitdir)) != normalized.review) throw FolderPublicationChanged()
        val serialized = gson.toJson(marker).toByteArray(Charsets.UTF_8)
        check(serialized.size <= 64 * 1024 * 1024) { "The folder review is too large to retain safely" }
        operation.ensureActive()
        if (!FolderPublicationFiles.exists(gitdir)) {
            check(capacity(root) >= estimate.additionalBytes) { "Not enough free space to prepare this publication. Free storage and retry." }
            Files.createDirectory(gitdir.toPath())
            writeMarker(root,marker,null)
        }
        initialize(root,marker)
        open(root).use { repository ->
            val headLock=LockFile(File(gitdir,"HEAD"))
            check(headLock.lock()) { "Another Git client is changing HEAD" }
            try {
                if (repository.fullBranch != "refs/heads/main" || repository.resolve("HEAD") != null) throw FolderPublicationChanged()
                val disk=repository.lockDirCache()
                try {
                    repository.newObjectInserter().use { inserter ->
                        val staged=DirCache.newInCore();val builder=staged.builder()
                        files.filter { it.path in normalized.selected }.sortedBy { it.path }.forEach { reviewed ->
                            operation.ensureActive()
                            val file=FolderPublicationFiles.file(root,reviewed.path)
                            check(file.length() <= FolderPublicationLimits.FILE_BYTES)
                            val bytes=FolderPublicationFiles.bytes(file,FolderPublicationLimits.FILE_BYTES.toInt())
                            if (bytes.size.toLong()!=reviewed.size || FolderPublicationFiles.digest(bytes)!=reviewed.digest || FolderPublicationFiles.executable(file)!=reviewed.executable) throw FolderPublicationChanged()
                            if (LfsPointer.parse(bytes)!=null) throw FolderPublicationUnsupported("LFS pointer files must be excluded from first publication")
                            builder.add(DirCacheEntry(reviewed.path).apply {
                                fileMode=if(reviewed.executable) FileMode.EXECUTABLE_FILE else FileMode.REGULAR_FILE
                                setObjectId(inserter.insert(Constants.OBJ_BLOB,bytes));length=bytes.size
                            })
                        }
                        builder.finish()
                        val tree=staged.writeTree(inserter)
                        if (disk.entryCount>0 && disk.writeTree(inserter)!=tree) throw FolderPublicationChanged()
                        inserter.flush()
                        if (FolderPublicationFiles.inspect(root,true)!=normalized.review) throw FolderPublicationChanged()
                        val commit=commit(marker,tree)
                        val oid=inserter.insert(commit);inserter.flush()
                        val prepared=validateCommit(repository,marker,oid.name,false)
                        val exclude=File(gitdir,"info/exclude")
                        val block="# BEGIN GitSync folder publication ${normalized.workflow}\n" + files.filter { it.path !in normalized.selected }.joinToString("\n") { literalIgnore(it.path) } + "\n# END GitSync folder publication\n"
                        val existing=if(FolderPublicationFiles.exists(exclude)) regular(exclude,32*1024*1024).toString(Charsets.UTF_8) else ""
                        if (existing.contains("# BEGIN GitSync folder publication ${normalized.workflow}")) {
                            if (!existing.contains(block)) throw FolderPublicationOwnership()
                        } else write(exclude,(existing+"\n"+block).toByteArray(),if(FolderPublicationFiles.exists(exclude)) existing.toByteArray() else null)
                        val diskBuilder=disk.builder()
                        for (i in 0 until staged.entryCount) diskBuilder.add(DirCacheEntry(staged.getEntry(i)))
                        diskBuilder.finish();disk.write();check(disk.commit()) { "Could not save the initial Git index" }
                        operation.ensureActive()
                        val update=repository.updateRef("refs/heads/main").apply { setExpectedOldObjectId(ObjectId.zeroId());setNewObjectId(oid);setRefLogMessage("Initial folder publication",false) }
                        if (update.update() != RefUpdate.Result.NEW) throw FolderPublicationChanged()
                        val next=marker.copy(prepared=prepared)
                        writeMarker(root,next,marker);marker=next
                        prepared
                    }
                } finally { disk.unlock() }
            } finally { headLock.unlock() }
        }
    }
    override suspend fun validatePrepared(path: String, workflow: String, expectedCommit: String): FolderPreparedCommit = withRepository(path, workflow) {
        val root=FolderPublicationFiles.validateRoot(path)
        val marker=readMarker(root,workflow)
        if (marker.prepared?.commit != expectedCommit) throw FolderPublicationOwnership()
        open(root).use { validateCommit(it,marker,expectedCommit) }
    }
    /** Receive-pack's live advertisement and old-OID comparison protect the first branch creation. */
    override suspend fun publish(path:String,workflow:String,expectedCommit:String,remoteUrl:String,credentials:Credentials) = withRepository(path, workflow) {
        val root=FolderPublicationFiles.validateRoot(path)
        val url=githubDestination(remoteUrl)
        open(root).use { repository ->
            configureOrigin(root,repository,workflow,expectedCommit,url)
            val marker=readMarker(root,workflow)
            val operation=currentCoroutineContext()
            val auth=ScopedCredentialsProvider(url,credentials)
            transport(repository,url).use { connectionTransport ->
                connectionTransport.credentialsProvider=auth;auth.configure(connectionTransport);connectionTransport.timeout=30
                connectionTransport.openPush().use { connection ->
                    validateCommit(repository,marker,expectedCommit)
                    assertOrigin(repository,url)
                    val actual=connection.refs.filter { it.name.startsWith("refs/") }
                    val main=actual.singleOrNull { it.name=="refs/heads/main" }
                    if(main?.objectId?.name==expectedCommit)return@withRepository
                    if(actual.isNotEmpty())throw FolderPublicationUnsupported("The destination already has history. Its refs were kept.")
                    operation.ensureActive()
                    val update=RemoteRefUpdate(repository,"refs/heads/main",ObjectId.fromString(expectedCommit),"refs/heads/main",false,null,ObjectId.zeroId())
                    try { connection.push(operation[com.bontecou.syncmd.services.git.TransferProgress]?.monitor() ?: NullProgressMonitor.INSTANCE,mapOf("refs/heads/main" to update)) }
                    catch(e:kotlinx.coroutines.CancellationException) { throw e }
                    catch(_:Exception) { throw FolderPublicationPushUnknown() }
                    if(update.newObjectId.name!=expectedCommit || update.remoteName!="refs/heads/main" || update.status !in listOf(RemoteRefUpdate.Status.OK,RemoteRefUpdate.Status.UP_TO_DATE)) throw FolderPublicationPushUnknown()
                }
            }
        }
    }
    /** Reconcile from live server refs before adding local tracking; no fetch can stage or replace newer files. */
    override suspend fun finish(path:String,workflow:String,expectedCommit:String,remoteUrl:String,credentials:Credentials) = withRepository(path, workflow, notifyChanges = true) {
        val root=FolderPublicationFiles.validateRoot(path);val url=githubDestination(remoteUrl)
        open(root).use { repository ->
            configureOrigin(root,repository,workflow,expectedCommit,url)
            val marker=readMarker(root,workflow)
            val auth=ScopedCredentialsProvider(url,credentials)
            transport(repository,url).use { remote ->
                remote.credentialsProvider=auth;auth.configure(remote);remote.timeout=30
                remote.openPush().use { advertised ->
                    if(advertised.getRef("refs/heads/main")?.objectId?.name!=expectedCommit)throw FolderPublicationChanged()
                }
            }
            validateCommit(repository,marker,expectedCommit);assertOrigin(repository,url)
            val tracking=repository.updateRef("refs/remotes/origin/main").apply {
                setExpectedOldObjectId(repository.resolve("refs/remotes/origin/main") ?: ObjectId.zeroId())
                setNewObjectId(ObjectId.fromString(expectedCommit))
            }
            if(tracking.update() !in listOf(RefUpdate.Result.NEW,RefUpdate.Result.NO_CHANGE,RefUpdate.Result.FAST_FORWARD))throw FolderPublicationChanged()
            updateConfig(repository) { config ->
                assertOrigin(config,url)
                val oldRemote=config.getString("branch","main","remote");val oldMerge=config.getString("branch","main","merge")
                if(oldRemote!=null && oldRemote!="origin" || oldMerge!=null && oldMerge!="refs/heads/main")throw FolderPublicationOwnership()
                config.setString("branch","main","remote","origin");config.setString("branch","main","merge","refs/heads/main")
            }
            currentCoroutineContext().ensureActive()
            // Display metadata is optional; the live remote acknowledgement above is mandatory.
            runCatching { com.bontecou.syncmd.services.git.RepositoryActivity.record(repository) }
            Unit
        }
    }
    private suspend fun <T> withRepository(path: String, workflow: String, notifyChanges: Boolean = false, action: suspend () -> T): T =
        FolderPublicationGate.withPublication(path, workflow) { RepositoryOperationCoordinator.withRepository(path) {
            try { action() } finally {
                if (notifyChanges) com.bontecou.syncmd.services.git.RepositoryUpdates.changed(path)
            }
        } }

    /** Called only after registration and the private COMPLETED record are durable. */
    override suspend fun clearPending(path: String, workflow: String, expectedCommit: String, remoteUrl: String) = withRepository(path, workflow, notifyChanges = true) {
        val root = FolderPublicationFiles.validateRoot(path)
        val target = File(root, ".git/folder-publication.json")
        open(root).use { repository ->
            if (!FolderPublicationFiles.exists(target)) {
                if (repository.config.getString("gitsync", null, "pendingPublication") != null) throw FolderPublicationOwnership()
                return@withRepository
            }
            val lock = LockFile(target)
            check(lock.lock()) { "Another client is updating publication metadata" }
            try {
                val before = regular(target, 64 * 1024 * 1024)
                val marker = readMarker(root, workflow)
                if (marker.prepared?.commit != expectedCommit || marker.remote != githubDestination(remoteUrl)) throw FolderPublicationOwnership()
                validateCommit(repository, marker, expectedCommit, checkHead = false)
                assertOrigin(repository, remoteUrl)
                updateConfig(repository) { config ->
                    val pending = config.getString("gitsync", null, "pendingPublication")
                    if (pending != null && pending != workflow) throw FolderPublicationOwnership()
                    config.unset("gitsync", null, "pendingPublication")
                }
                if (!regular(target, 64 * 1024 * 1024).contentEquals(before)) throw FolderPublicationOwnership()
                Files.delete(target.toPath())
            } finally { lock.unlock() }
        }
    }

    private fun configureOrigin(root:File,repository:Repository,workflow:String,expectedCommit:String,url:String) {
        val marker=readMarker(root,workflow)
        if(marker.prepared?.commit!=expectedCommit || marker.remote!=null && marker.remote!=url)throw FolderPublicationOwnership()
        validateCommit(repository,marker,expectedCommit)
        assertOrigin(repository.config,url,true)
        if(marker.remote==null)writeMarker(root,marker.copy(remote=url),marker)
        updateConfig(repository) { config ->
            assertOrigin(config,url,true)
            config.setString("remote","origin","url",url)
            config.setString("remote","origin","fetch","+refs/heads/*:refs/remotes/origin/*")
        }
    }
    private fun assertOrigin(repository:Repository,url:String) = assertOrigin(repository.config,url)
    private fun assertOrigin(config:Config,url:String,allowAbsent:Boolean=false) {
        val urls=config.getStringList("remote","origin","url").toList()
        val pushes=config.getStringList("remote","origin","pushurl").toList()
        if(!(allowAbsent && urls.isEmpty() || urls==listOf(url)) || pushes.isNotEmpty() && pushes!=listOf(url))throw FolderPublicationOwnership()
    }
    private fun updateConfig(repository:Repository,change:(Config)->Unit) {
        val file=File(repository.directory,"config");val before=regular(file,1024*1024)
        val config=Config().apply { fromText(before.toString(Charsets.UTF_8)) };change(config)
        write(file,config.toText().toByteArray(),before)
        repository.config.load()
    }
    private fun initialize(root: File,marker: Marker) {
        val gitdir=File(root,".git")
        listOf("objects","objects/info","objects/pack","refs","refs/heads","refs/tags","info").forEach { path ->
            val dir=File(gitdir,path)
            if (FolderPublicationFiles.exists(dir)) { if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) throw FolderPublicationOwnership() }
            else Files.createDirectory(dir.toPath())
        }
        val config=Config().apply {
            setInt("core",null,"repositoryformatversion",0);setBoolean("core",null,"bare",false);setBoolean("core",null,"filemode",false)
            setBoolean("core",null,"logallrefupdates",true);setBoolean("core",null,"autocrlf",false)
            setString("user",null,"name",marker.input.authorName);setString("user",null,"email",marker.input.authorEmail)
            setString("gitsync",null,"pendingPublication",marker.input.workflow)
        }.toText()
        newOrSame(File(gitdir,"config"),config.toByteArray())
        newOrSame(File(gitdir,"HEAD"),"ref: refs/heads/main\n".toByteArray())
    }
    private fun validateCommit(repository: Repository,marker: Marker,oid: String,checkHead: Boolean = true): FolderPreparedCommit {
        if (!oid.matches(Regex("[a-f0-9]{40}"))) throw FolderPublicationOwnership()
        if (checkHead && (repository.fullBranch!="refs/heads/main" || repository.resolve("HEAD")?.name!=oid)) throw FolderPublicationOwnership()
        RevWalk(repository).use { walk ->
            val saved=walk.parseCommit(ObjectId.fromString(oid))
            if (saved.parentCount!=0 || repository.newObjectInserter().use { it.idFor(Constants.OBJ_COMMIT,commit(marker,saved.tree.id).build()) }.name!=oid) throw FolderPublicationOwnership()
            val expected=marker.input.review.files.filter { it.path in marker.input.selected }.associateBy { it.path }
            val seen=mutableSetOf<String>()
            TreeWalk(repository).use { tree ->
                tree.addTree(saved.tree);tree.isRecursive=true
                while(tree.next()) {
                    val file=expected[tree.pathString] ?: throw FolderPublicationOwnership()
                    if (!seen.add(file.path) || !file.eligible || tree.getFileMode(0)!=(if(file.executable) FileMode.EXECUTABLE_FILE else FileMode.REGULAR_FILE)) throw FolderPublicationOwnership()
                    val loader=repository.open(tree.getObjectId(0),Constants.OBJ_BLOB)
                    if(loader.size!=file.size || loader.size>FolderPublicationLimits.FILE_BYTES) throw FolderPublicationOwnership()
                    val bytes=loader.getBytes(FolderPublicationLimits.FILE_BYTES.toInt())
                    if(FolderPublicationFiles.digest(bytes)!=file.digest || LfsPointer.parse(bytes)!=null) throw FolderPublicationOwnership()
                }
            }
            if(seen!=marker.input.selected || seen.size!=expected.size) throw FolderPublicationOwnership()
            FolderPublicationFiles.inspectionRepository(repository.workTree,File(repository.directory,"objects")) { inspection ->
                RevWalk(inspection).use { isolated ->
                    val snapshot=isolated.parseCommit(saved.id)
                    expected.keys.forEach { path ->
                        if(FolderPublicationFiles.unsupported(LfsFactory.getAttributesForPath(inspection,path,snapshot))!=null) throw FolderPublicationUnsupported("The saved commit contains unsupported content-conversion rules")
                    }
                }
            }
            return FolderPreparedCommit(oid,saved.tree.name)
        }
    }
    private fun commit(marker: Marker,tree: ObjectId): CommitBuilder {
        val signature=PersonIdent(marker.input.authorName,marker.input.authorEmail,Date.from(Instant.ofEpochSecond(marker.timestamp)),TimeZone.getTimeZone("UTC"))
        return CommitBuilder().apply { setTreeId(tree);setAuthor(signature);setCommitter(signature);setMessage(marker.input.message) }
    }
    private fun open(root: File): Repository {
        val gitdir=File(root,".git")
        if (!gitdir.isDirectory || Files.isSymbolicLink(gitdir.toPath()) || gitdir.canonicalFile!=gitdir.absoluteFile) throw FolderPublicationOwnership()
        return FileRepositoryBuilder().setGitDir(gitdir).setWorkTree(root).setMustExist(true).build()
    }
    private fun readMarker(root: File,workflow: String): Marker {
        try {
            val gitdir=File(root,".git")
            if(!gitdir.isDirectory || Files.isSymbolicLink(gitdir.toPath())) throw FolderPublicationOwnership()
            val marker=gson.fromJson(regular(File(gitdir,"folder-publication.json"),64*1024*1024).toString(Charsets.UTF_8),Marker::class.java)
            if(marker.schema!=1 || marker.input.workflow!=workflow || marker.timestamp<=0 || marker.input.review.files.size>FolderPublicationLimits.INSPECTED_FILES || marker.input.selected.isEmpty()) throw FolderPublicationOwnership()
            return marker
        } catch(e:FolderPublicationOwnership) { throw e } catch(_:Exception) { throw FolderPublicationOwnership() }
    }
    private fun writeMarker(root: File,next: Marker,previous: Marker?) {
        val target=File(root,".git/folder-publication.json")
        val before=if(previous==null) null else regular(target,64*1024*1024).also { if(gson.fromJson(it.toString(Charsets.UTF_8),Marker::class.java)!=previous) throw FolderPublicationOwnership() }
        val bytes=gson.toJson(next).toByteArray(Charsets.UTF_8)
        check(bytes.size<=64*1024*1024)
        write(target,bytes,before)
    }
    private fun newOrSame(file:File,bytes:ByteArray) {
        if(FolderPublicationFiles.exists(file)) { if(!regular(file,1024*1024).contentEquals(bytes)) throw FolderPublicationOwnership() }
        else write(file,bytes,null)
    }
    private fun write(file: File,bytes: ByteArray,expected: ByteArray?) {
        val lock=LockFile(file);check(lock.lock()) { "Another client is updating publication metadata" }
        try {
            fun unchanged() = if(expected==null) !FolderPublicationFiles.exists(file) else regular(file,64*1024*1024).contentEquals(expected)
            if(!unchanged())throw FolderPublicationOwnership()
            lock.setFSync(true);lock.write(bytes)
            if(!unchanged())throw FolderPublicationOwnership()
            check(lock.commit()) { "Could not retain publication progress" }
        } finally { lock.unlock() }
    }
    private fun regular(file: File,limit:Int): ByteArray {
        if(Files.isSymbolicLink(file.toPath()) || file.canonicalFile!=file.absoluteFile || !file.isFile || file.length()>limit) throw FolderPublicationOwnership()
        return file.inputStream().use { input ->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
            while(true) { val count=input.read(buffer);if(count==-1)break;if(output.size()>limit-count)throw FolderPublicationOwnership();output.write(buffer,0,count) }
            output.toByteArray()
        }
    }
    private fun literalIgnore(path:String) = "/" + path.flatMap { if(it in "\\*?[]#! \t") listOf('\\',it) else listOf(it) }.joinToString("")
    companion object {
        private fun githubDestination(value:String):String {
            val endpoint=HttpsRemote.parse(value)
            val parts=endpoint.path.split('/')
            require(endpoint.host=="github.com" && endpoint.port==443 && endpoint.url==value && parts.size==3 && parts[1].matches(Regex("[A-Za-z0-9_.-]{1,100}")) &&
                parts[2].endsWith(".git") && parts[2].removeSuffix(".git").let { it!="." && it!=".." && it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }) { "Choose the canonical GitHub HTTPS destination" }
            return endpoint.url
        }
    }
}
