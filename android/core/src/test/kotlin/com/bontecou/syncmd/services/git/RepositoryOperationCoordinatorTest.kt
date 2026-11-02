package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RepositoryOperationCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `canonical aliases share the same lock across callers`() = runBlocking {
        val root = temporary.newFolder("repo")
        val inside = AtomicInteger()
        val maximum = AtomicInteger()
        (1..40).map { i -> async(Dispatchers.Default) {
            val path = if (i % 2 == 0) root.path else File(root, "./").path
            RepositoryOperationCoordinator.withRepository(path) {
                val active = inside.incrementAndGet()
                maximum.accumulateAndGet(active, ::maxOf)
                Thread.yield()
                inside.decrementAndGet()
            }
        } }.awaitAll()
        assertThat(maximum.get()).isEqualTo(1)
    }

    @Test fun `nested calls on the same coroutine do not deadlock`() = runBlocking {
        val path = temporary.newFolder("repo").path
        val answer = RepositoryOperationCoordinator.withRepository(path) {
            RepositoryOperationCoordinator.withRepository(path) { 42 }
        }
        assertThat(answer).isEqualTo(42)
    }

    @Test fun `cancelled waiter does not unlock the active operation`() = runBlocking {
        val path = temporary.newFolder("repo").path
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            RepositoryOperationCoordinator.withRepository(path) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val waiter = launch { RepositoryOperationCoordinator.withRepository(path) { error("Cancelled waiter ran") } }
        waiter.cancelAndJoin()
        val third = async { RepositoryOperationCoordinator.withRepository(path) { "done" } }
        assertThat(third.isCompleted).isFalse()
        release.complete(Unit)
        first.join()
        assertThat(third.await()).isEqualTo("done")
    }

    @Test fun `different repositories can run independently`() = runBlocking {
        val a = temporary.newFolder("a").path
        val b = temporary.newFolder("b").path
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { RepositoryOperationCoordinator.withRepository(a) { entered.complete(Unit); release.await() } }
        entered.await()
        assertThat(RepositoryOperationCoordinator.withRepository(b) { "independent" }).isEqualTo("independent")
        release.complete(Unit)
        first.join()
    }

    @Test fun `child coroutine cannot inherit authorization to bypass the lock`() = runBlocking {
        val path = temporary.newFolder("repo").path
        RepositoryOperationCoordinator.withRepository(path) {
            coroutineScope {
                val result = async { runCatching { RepositoryOperationCoordinator.withRepository(path) { error("must not run") } } }
                assertThat(result.await().exceptionOrNull()?.message).contains("sequentially")
            }
        }
    }
}
