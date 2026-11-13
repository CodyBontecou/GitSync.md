package com.bontecou.syncmd.services.git

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.internal.storage.file.LockFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Binds a failed checkout to its unchanged Git step and idempotent retry todo. */
internal object RebaseCheckoutJournal {
    private const val NAME="gitsync-checkout.json"
    private const val MAX_MARKER=64*1024
    private const val MAX_TODO=2*1024*1024
    val gitStateFiles=listOf("MERGE_HEAD","MERGE_MSG","CHERRY_PICK_HEAD","REVERT_HEAD","ORIG_HEAD","SQUASH_MSG")
    private data class Record(val version:Int=1,val paths:List<String>,val total:Int,
        val pick:String?=null,val head:String?=null,val index:String?=null,val todo:String?=null,val queued:String?=null,val context:String?=null)
    data class State(val paths:List<String>,val total:Int,val retryable:Boolean)
    private fun folder(repository:Repository):File? {
        val folder=File(repository.directory,"rebase-merge")
        if(!Files.exists(folder.toPath(),NOFOLLOW_LINKS))return null
        check(Files.isDirectory(folder.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(folder.toPath())) { "Linked checkout recovery needs manual review" }
        return folder
    }
    private fun bytes(file:File,limit:Int):ByteArray {
        check(Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file.toPath())) { "Checkout recovery metadata needs manual review" }
        return Files.newInputStream(file.toPath(),NOFOLLOW_LINKS).use { input ->
            val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer)
                if(count<0)break
                check(result.size()+count<=limit) { "Checkout recovery metadata is too large" }
                result.write(buffer,0,count)
            }
            result.toByteArray()
        }
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun index(repository:Repository):String {
        val digest=MessageDigest.getInstance("SHA-256")
        Files.newInputStream(File(repository.directory,"index").toPath(),NOFOLLOW_LINKS).use { input ->
            val buffer=ByteArray(8192);while(true) { val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun retryLine(pick:String)="pick $pick Retry blocked checkout\n".toByteArray(Charsets.UTF_8)
    private fun progress(repository:Repository,dir:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun add(value:String) { digest.update(value.toByteArray(Charsets.UTF_8));digest.update(0.toByte()) }
        fun file(file:File,name:String) {
            check(!Files.isSymbolicLink(file.toPath())) { "Linked replay progress needs manual review" }
            add(name)
            if(Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS)) {
                add(file.length().toString())
                Files.newInputStream(file.toPath(),NOFOLLOW_LINKS).use { input ->
                    val buffer=ByteArray(8192);while(true) { val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) }
                }
                digest.update(0.toByte())
            }
        }
        dir.walkTopDown().sortedBy { it.path }.forEach { entry ->
            val name=entry.relativeTo(dir).path
            if(name !in setOf(NAME,"git-rebase-todo"))file(entry,name)
        }
        gitStateFiles.forEach { file(File(repository.directory,it),"git/$it") }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun encode(record:Record):ByteArray {
        val json=JsonObject();json.addProperty("version",record.version);json.addProperty("total",record.total)
        json.add("paths",JsonArray().apply { record.paths.forEach { add(it) } })
        mapOf("pick" to record.pick,"head" to record.head,"index" to record.index,"todo" to record.todo,"queued" to record.queued,"context" to record.context)
            .forEach { (key,value) -> if(value!=null)json.addProperty(key,value) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }
    private fun readRecord(file:File):Record? = runCatching {
        val json=JsonParser.parseString(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes(file,MAX_MARKER))).toString()).asJsonObject
        check(json.keySet().all { it in setOf("version","paths","total","pick","head","index","todo","queued","context") })
        check(json["version"].asInt==1)
        val paths=json["paths"].asJsonArray.map { check(it.isJsonPrimitive&&it.asJsonPrimitive.isString);it.asString.also { path -> check(path.length<=8192) } }
        val total=json["total"].asInt;check(paths.size<=100&&total>=paths.size&&total<=1_000_000)
        fun id(name:String,length:Int):String?=json[name]?.let { value ->
            check(value.isJsonPrimitive&&value.asJsonPrimitive.isString)
            value.asString.also { check(it.matches(Regex("[0-9a-f]{$length}"))) }
        }
        Record(paths=paths,total=total,pick=id("pick",40),head=id("head",40),index=id("index",64),todo=id("todo",64),queued=id("queued",64),context=id("context",64))
    }.getOrNull()
    private fun state(git:Git,dir:File,record:Record):State? {
        val pick=record.pick ?: return State(record.paths,record.total,false)
        val stopped=runCatching { bytes(File(dir,"stopped-sha"),128).toString(Charsets.US_ASCII).trim() }.getOrNull()
            ?: return State(record.paths,record.total,false)
        if(!stopped.matches(Regex("[0-9a-fA-F]{7,40}")))return State(record.paths,record.total,false)
        if(!pick.startsWith(stopped,ignoreCase=true))return null // Another Git step superseded this marker.
        val ready=runCatching {
            val todo=hash(bytes(File(dir,"git-rebase-todo"),MAX_TODO))
            git.repository.fullBranch==record.head&&git.repository.resolve("HEAD")?.name==record.head&&index(git.repository)==record.index&&
                todo in setOf(record.todo,record.queued)&&progress(git.repository,dir)==record.context&&!git.status().call().hasUncommittedChanges()
        }.getOrDefault(false)
        return State(record.paths,record.total,ready)
    }
    fun read(git:Git):State? {
        val dir=folder(git.repository) ?: return null
        val file=File(dir,NAME)
        if(!Files.exists(file.toPath(),NOFOLLOW_LINKS)) {
            return if(Files.exists(File(file.path+".lock").toPath(),NOFOLLOW_LINKS))State(emptyList(),0,false) else null
        }
        val record=readRecord(file) ?: return State(emptyList(),0,false)
        return state(git,dir,record)
    }
    fun save(git:Git,picked:RevCommit?,paths:Set<String>):State {
        val dir=folder(git.repository) ?: error("No paused rebase for checkout recovery")
        val names=paths.sorted().take(100).toMutableList()
        val bound=runCatching {
            val pick=requireNotNull(picked).name
            val stopped=bytes(File(dir,"stopped-sha"),128).toString(Charsets.US_ASCII).trim()
            check(stopped.matches(Regex("[0-9a-fA-F]{7,40}"))&&pick.startsWith(stopped,ignoreCase=true))
            val todo=bytes(File(dir,"git-rebase-todo"),MAX_TODO)
            Record(paths=names.toList(),total=paths.size,pick=pick,head=requireNotNull(git.repository.resolve("HEAD")).name,
                index=index(git.repository),todo=hash(todo),queued=hash(retryLine(pick)+todo))
                .copy(context=progress(git.repository,dir))
        }.getOrElse { Record(paths=names.toList(),total=paths.size) }
        var record=bound;var encoded=encode(record)
        while(encoded.size>MAX_MARKER&&names.isNotEmpty()) {
            names.removeAt(names.lastIndex);record=bound.copy(paths=names.toList());encoded=encode(record)
        }
        check(encoded.size<=MAX_MARKER)
        atomicWrite(dir,File(dir,NAME),encoded)
        return state(git,dir,record) ?: State(record.paths,record.total,false)
    }
    fun queue(git:Git) {
        val dir=folder(git.repository) ?: error("The paused rebase is unavailable")
        val record=readRecord(File(dir,NAME)) ?: error("Checkout recovery needs manual review")
        check(state(git,dir,record)?.retryable==true) { "The checkout recovery changed. Keep your edits and refresh before retrying." }
        val todo=File(dir,"git-rebase-todo");val original=bytes(todo,MAX_TODO);val digest=hash(original)
        if(digest==record.queued)return // A prior attempt queued this pick before process interruption.
        check(digest==record.todo)
        val queued=retryLine(requireNotNull(record.pick))+original
        check(queued.size<=MAX_TODO) { "The Git replay plan is too large for automatic checkout retry" }
        atomicWrite(dir,todo,queued,expected=digest)
    }
    fun clear(repository:Repository) {
        val dir=folder(repository) ?: return
        val marker=File(dir,NAME)
        check(!Files.isSymbolicLink(marker.toPath())) { "Linked checkout recovery needs manual review" }
        Files.deleteIfExists(marker.toPath())
    }
    private fun atomicWrite(dir:File,target:File,value:ByteArray,expected:String?=null) {
        val identity=Files.readAttributes(dir.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey()?.toString()
        val lock=LockFile(target)
        check(lock.lock()) { "Checkout recovery metadata is locked by another Git operation" }
        try {
            lock.setFSync(true);lock.write(value)
            check(!Files.isSymbolicLink(dir.toPath())&&!Files.isSymbolicLink(target.toPath())) { "Checkout recovery path changed" }
            check(Files.readAttributes(dir.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey()?.toString()==identity) { "Rebase metadata changed" }
            if(expected!=null)check(hash(bytes(target,MAX_TODO))==expected) { "The Git replay plan changed before retrying" }
            check(lock.commit()) { "Checkout recovery metadata could not be published" }
        } finally { lock.unlock() }
    }
}
