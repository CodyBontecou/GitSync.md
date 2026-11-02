package com.bontecou.syncmd.services.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Serializes complete operations across all service instances using the same working tree. */
object RepositoryOperationCoordinator {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private class Lease(val paths: Set<String>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
        var job: Job? = null
    }
    private val entries = mutableMapOf<String, Entry>()
    @Volatile private var accessCheck: ((String) -> Unit)? = null
    /** The host supplies its platform storage policy; core/JVM clients need no Android dependency. */
    fun installAccessCheck(check: (String) -> Unit) { accessCheck = check }

    /** Apply the same platform policy to a local transport endpoint outside the current working-copy lease. */
    fun requireAccess(path: String) {
        val canonical = File(path).canonicalPath
        accessCheck?.invoke(canonical)
    }

    suspend fun <T> withRepository(path: String, diagnostic: com.bontecou.syncmd.services.diagnostics.DiagnosticOperation? = null,
        operation: suspend () -> T): T = withRepositories(listOf(path)) {
        com.bontecou.syncmd.services.diagnostics.Diagnostics.track(diagnostic) {
            try {
                operation().also { result ->
                    currentCoroutineContext().ensureActive()
                    RepositoryActivity.completed(path, diagnostic, result)
                }
            } finally {
                if (diagnostic != null) RepositoryUpdates.changed(path)
            }
        }
    }

    /** Reserve both sides of a relocation in canonical order, avoiding opposing-move deadlocks. */
    suspend fun <T> withRepositories(paths: Collection<String>, operation: suspend () -> T): T {
        val canonical = paths.map { File(it).canonicalPath }.distinct().sorted()
        require(canonical.isNotEmpty())
        val context = currentCoroutineContext()
        val inherited = context[Lease]
        if (inherited != null && inherited.paths.containsAll(canonical)) {
            check(inherited.job === context[Job]) {
                "Repository operations inside a lease must run sequentially, not in child coroutines"
            }
            context.ensureActive()
            canonical.forEach { accessCheck?.invoke(it) }
            canonical.forEach { com.bontecou.syncmd.services.publication.FolderPublicationGate.check(it) }
            return operation().also { currentCoroutineContext().ensureActive() }
        }
        check(inherited == null) { "Complete the current repository operation before accessing another repository" }
        val reserved = synchronized(entries) {
            canonical.map { path -> path to entries.getOrPut(path) { Entry() }.also { it.users++ } }
        }
        val acquired = mutableListOf<Entry>()
        try {
            reserved.forEach { (_, entry) -> entry.mutex.lock(); acquired += entry }
            try {
                val lease = Lease(canonical.toSet())
                return withContext(Dispatchers.IO + lease) {
                    lease.job = currentCoroutineContext()[Job]
                    canonical.forEach { accessCheck?.invoke(it) }
                    canonical.forEach { com.bontecou.syncmd.services.publication.FolderPublicationGate.check(it) }
                    operation().also { currentCoroutineContext().ensureActive() }
                }
            } finally {
                acquired.asReversed().forEach { it.mutex.unlock() }
                acquired.clear()
            }
        } finally {
            acquired.asReversed().forEach { it.mutex.unlock() }
            synchronized(entries) { reserved.forEach { (path, entry) ->
                entry.users--
                if (entry.users == 0) entries.remove(path)
            }
            }
        }
    }
}
