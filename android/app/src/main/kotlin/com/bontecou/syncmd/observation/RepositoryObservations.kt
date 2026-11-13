package com.bontecou.syncmd.observation

import android.os.FileObserver
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.RepositoryUpdates
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared only by visible subscribers. No background service, sync or file contents are retained. */
object RepositoryObservations {
    private val hub = RepositoryObservationHub()
    fun observe(path: String): Flow<Unit> = hub.observe(path)
    internal fun activeRootCount() = hub.activeRootCount()
    internal fun watcherCount() = DirectoryWatchPool.count()
}

internal class RepositoryObservationHub(
    private val fallbackMillis: Long = 30_000,
    private val settleMillis: Long = 200,
    private val maxDirectories: Int = 2048,
) {
    init { require(fallbackMillis > 0 && settleMillis >= 0 && maxDirectories > 0) }
    private val entries = mutableMapOf<String, Entry>()
    private val watchers = AtomicInteger()
    private inner class Entry(val path: String) {
        val listeners = mutableSetOf<Channel<Unit>>()
        val ready = CompletableDeferred<Unit>()
        lateinit var job: Job
        val publishedAt = AtomicLong()
        fun changed() = synchronized(entries) {
            publishedAt.set(System.nanoTime())
            listeners.forEach { it.trySend(Unit) }; Unit
        }
    }
    internal fun activeRootCount(): Int = synchronized(entries) { entries.size }
    internal fun watcherCount(): Int = watchers.get()

    fun observe(path: String): Flow<Unit> = flow {
        val canonical = File(path).canonicalPath
        val pending = Channel<Unit>(Channel.CONFLATED)
        val entry = synchronized(entries) {
            entries.getOrPut(canonical) {
                Entry(canonical).also { created ->
                    created.job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            coroutineScope {
                                val registered = CompletableDeferred<Unit>()
                                val appChanges = launch {
                                    var initial = true
                                    RepositoryUpdates.observe(canonical).collect {
                                        if (initial) { initial=false;registered.complete(Unit) }
                                        else created.changed()
                                    }
                                }
                                registered.await()
                                try { monitor(created) } finally { appChanges.cancelAndJoin() }
                            }
                        } finally { created.ready.complete(Unit) }
                    }
                }
            }.also { it.listeners.add(pending) }
        }
        try {
            // Install directory watches before the initial read. Changes during
            // that read remain conflated in the subscriber's pending channel.
            entry.ready.await()
            pending.trySend(Unit)
            for (signal in pending) emit(signal)
        } finally {
            val stop = synchronized(entries) {
                entry.listeners.remove(pending)
                if (entry.listeners.isEmpty()) { entries.remove(canonical); entry.job } else null
            }
            pending.close()
            withContext(NonCancellable) { stop?.cancelAndJoin() }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun monitor(entry: Entry) {
        val events = Channel<Unit>(Channel.CONFLATED)
        val rescan = AtomicBoolean()
        val lastEventAt = AtomicLong()
        val candidates = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val watchedDirectories = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val installed = mutableListOf<AutoCloseable>()
        val root = File(entry.path)
        var rootIdentity: String? = null
        var available = false
        fun clear() {
            installed.forEach { runCatching { it.close() }; watchers.decrementAndGet() }
            installed.clear();watchedDirectories.clear()
        }
        fun readRootIdentity(): String {
            val attributes = Files.readAttributes(root.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            check(attributes.isDirectory && !attributes.isSymbolicLink)
            return attributes.fileKey()?.toString() ?: error("Folder identity unavailable")
        }
        fun watch(folder: File, parent: Boolean = false) {
            val observer = DirectoryWatchPool.acquire(folder,MASK) { event,path ->
                if (event and MASK != 0 && (!parent || path == root.name)) {
                    val ignoredMetadata = !parent && folder.name == ".git" && path != null &&
                        (path.endsWith(".lock") || path.substringBefore('/') in setOf("objects","logs","lfs"))
                    if (!ignoredMetadata) {
                        if (parent || path == null) rescan.set(true)
                        else if (event and STRUCTURE != 0) {
                            val affected = File(folder,path).path
                            if (affected in watchedDirectories || candidates.size >= 256) rescan.set(true)
                            else candidates.add(affected)
                        }
                        lastEventAt.set(System.nanoTime())
                        events.trySend(Unit)
                    }
                }
            }
            installed += observer;watchers.incrementAndGet();watchedDirectories.add(folder.path)
        }
        suspend fun rebuild(): Boolean {
            clear()
            return try {
                RepositoryOperationCoordinator.withRepository(entry.path) {
                    rootIdentity = readRootIdentity()
                    val queue = java.util.ArrayDeque<File>();queue.add(root)
                    var visited = 0
                    while (queue.isNotEmpty() && installed.size < maxDirectories && visited < MAX_ENTRIES) {
                        currentCoroutineContext().ensureActive()
                        val folder = queue.removeFirst()
                        if (!Files.isDirectory(folder.toPath(),NOFOLLOW_LINKS) || Files.isSymbolicLink(folder.toPath())) continue
                        watch(folder)
                        Files.newDirectoryStream(folder.toPath()).use { children ->
                            for (child in children) {
                                if (++visited >= MAX_ENTRIES) break
                                if (folder.name == ".git" && child.fileName.toString() !in setOf("refs","rebase-merge","rebase-apply")) continue
                                if (Files.isDirectory(child,NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) queue.add(child.toFile())
                            }
                        }
                    }
                    root.parentFile?.takeIf { Files.isDirectory(it.toPath(),NOFOLLOW_LINKS) && !Files.isSymbolicLink(it.toPath()) }?.let { watch(it,parent=true) }
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { clear();rootIdentity=null;false }
        }
        try {
            available = rebuild();entry.ready.complete(Unit)
            while (currentCoroutineContext().isActive) {
                val event = withTimeoutOrNull(fallbackMillis) { events.receive() }
                if (event != null) {
                    delay(settleMillis)
                    while (events.tryReceive().isSuccess) Unit
                }
                val wasAvailable = available
                val changedDirectories = candidates.toList()
                changedDirectories.forEach { candidates.remove(it) }
                if (changedDirectories.any { Files.isDirectory(File(it).toPath(),NOFOLLOW_LINKS) }) rescan.set(true)
                available = if (rescan.getAndSet(false) || !available) rebuild() else try {
                    RepositoryOperationCoordinator.withRepository(entry.path) { check(readRootIdentity() == rootIdentity) };true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { rebuild() }
                // The periodic invalidation also covers dropped inotify events,
                // watch limits and filesystems that do not deliver reliable events.
                if ((available || wasAvailable) && (event == null || available != wasAvailable || lastEventAt.get() > entry.publishedAt.get())) entry.changed()
            }
        } finally {
            entry.ready.complete(Unit);events.close();clear()
        }
    }
    private companion object {
        const val MAX_ENTRIES = 50_000
        const val STRUCTURE = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        const val MASK = STRUCTURE or FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.ATTRIB
    }
}

/** Android keeps one callback for each native descriptor, even for different
 * FileObserver objects watching the same inode. Share the native watch itself. */
private object DirectoryWatchPool {
    private class Entry(val mask: Int) {
        val callbacks = mutableSetOf<(Int,String?) -> Unit>()
        lateinit var observer: FileObserver
    }
    private val entries = mutableMapOf<String,Entry>()
    fun count(): Int = synchronized(entries) { entries.size }
    fun acquire(folder: File, mask: Int, callback: (Int,String?) -> Unit): AutoCloseable {
        val attributes = Files.readAttributes(folder.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        check(attributes.isDirectory && !attributes.isSymbolicLink)
        val identity = attributes.fileKey()?.toString() ?: error("Directory identity unavailable")
        val entry = synchronized(entries) {
            entries[identity]?.also { check(it.mask == mask);it.callbacks.add(callback) } ?: Entry(mask).also { created ->
                created.callbacks.add(callback)
                @Suppress("DEPRECATION")
                val observer = object : FileObserver(folder.path,mask) {
                    override fun onEvent(event: Int, path: String?) {
                        val callbacks = synchronized(entries) { created.callbacks.toList() }
                        callbacks.forEach { runCatching { it(event,path) } }
                    }
                }
                created.observer=observer;entries[identity]=created
                try { observer.startWatching() }
                catch (failure: Exception) { entries.remove(identity);created.callbacks.clear();observer.stopWatching();throw failure }
            }
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false,true)) synchronized(entries) {
                entry.callbacks.remove(callback)
                if (entry.callbacks.isEmpty()) {
                    entries.remove(identity)
                    entry.observer.stopWatching()
                }
            }
        }
    }
}
