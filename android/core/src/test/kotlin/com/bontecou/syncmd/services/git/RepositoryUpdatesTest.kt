package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import com.bontecou.syncmd.services.diagnostics.DiagnosticOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RepositoryUpdatesTest {
    @get:Rule val temporary=TemporaryFolder()
    private suspend fun receive(channel:Channel<Unit>) = withTimeout(5_000) { channel.receive() }
    @Test fun `initial read registration queues a write that happens during that read`() = runBlocking {
        val path=temporary.newFolder().path;val signals=Channel<Unit>(Channel.UNLIMITED)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=launch(Dispatchers.Default) { var first=true;RepositoryUpdates.observe(path).collect {
            if(first) { first=false;entered.complete(Unit);release.await() };signals.send(Unit)
        } }
        try {
            entered.await();RepositoryUpdates.changed(path);release.complete(Unit)
            receive(signals);receive(signals);assertThat(signals.tryReceive().isFailure).isTrue()
        } finally { release.complete(Unit);job.cancelAndJoin() }
    }
    @Test fun `canonical aliases share updates while unrelated working copies do not`() = runBlocking {
        val root=temporary.newFolder();val alias=File(temporary.newFolder(),"alias")
        Files.createSymbolicLink(alias.toPath(),root.toPath())
        val other=temporary.newFolder();val a=Channel<Unit>(Channel.UNLIMITED);val b=Channel<Unit>(Channel.UNLIMITED);val c=Channel<Unit>(Channel.UNLIMITED)
        val jobs=listOf(root.path to a,alias.path to b,other.path to c).map { (path,channel)->launch(Dispatchers.Default) { RepositoryUpdates.observe(path).collect { channel.send(Unit) } } }
        try {
            receive(a);receive(b);receive(c);RepositoryUpdates.changed(alias.path)
            receive(a);receive(b);assertThat(c.tryReceive().isFailure).isTrue()
        } finally { jobs.forEach { it.cancelAndJoin() } }
    }
    @Test fun `slow subscribers conflate bursts without losing the final refresh`() = runBlocking {
        val path=temporary.newFolder().path;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val signals=Channel<Unit>(Channel.UNLIMITED)
        val job=launch(Dispatchers.Default) { var first=true;RepositoryUpdates.observe(path).collect {
            if(first) { first=false;entered.complete(Unit);release.await() };signals.send(Unit)
        } }
        try {
            entered.await();repeat(1000) { RepositoryUpdates.changed(path) };release.complete(Unit)
            receive(signals);receive(signals);assertThat(signals.tryReceive().isFailure).isTrue()
        } finally { release.complete(Unit);job.cancelAndJoin() }
    }
    @Test fun `diagnosed writes notify on success and returned failure while reads never loop`() = runBlocking {
        val root=SampleRepository.openOrCreate(temporary.newFolder());val signals=Channel<Unit>(Channel.UNLIMITED)
        val job=launch(Dispatchers.Default) { RepositoryUpdates.observe(root.path).collect { signals.send(Unit) } }
        fun time()=Git.open(root).use { RepositoryActivity.read(it.repository) }
        try {
            receive(signals)
            RepositoryOverview.read(root.path);LocalPullRepository().getStatus(root.path).getOrThrow()
            assertThat(signals.tryReceive().isFailure).isTrue()
            LocalDiffRepository().stageAll(root.path).getOrThrow();receive(signals);assertThat(time()).isNull()
            LocalDiffRepository().commit(root.path,"Live update","Fixture","fixture@example.invalid").getOrThrow();receive(signals)
            val recorded=time();assertThat(recorded).isNotNull()
            assertThat(LocalDiffRepository().commit(root.path,"","Fixture","fixture@example.invalid").isFailure).isTrue()
            receive(signals);assertThat(time()).isEqualTo(recorded)
        } finally { job.cancelAndJoin() }
    }
    @Test fun `throwing and cancelled writes still invalidate without inventing completion`() = runBlocking {
        val root=SampleRepository.openOrCreate(temporary.newFolder());val signals=Channel<Unit>(Channel.UNLIMITED)
        val job=launch(Dispatchers.Default) { RepositoryUpdates.observe(root.path).collect { signals.send(Unit) } }
        try {
            receive(signals)
            assertThat(runCatching { RepositoryOperationCoordinator.withRepository(root.path,DiagnosticOperation.BRANCH) { error("Stopped fixture") } }.isFailure).isTrue()
            receive(signals)
            assertThat(runCatching { RepositoryOperationCoordinator.withRepository(root.path,DiagnosticOperation.BRANCH) { throw CancellationException("Cancelled fixture") } }.isFailure).isTrue()
            receive(signals);Git.open(root).use { assertThat(RepositoryActivity.read(it.repository)).isNull() }
        } finally { job.cancelAndJoin() }
    }
    @Test fun `cancelled subscriptions release all retained paths and later subscribers start fresh`() = runBlocking {
        val baseline=RepositoryUpdates.subscriptionCount()
        repeat(100) { val path=File(temporary.root,"unretained-$it").path;RepositoryUpdates.observe(path).first();RepositoryUpdates.changed(path) }
        assertThat(RepositoryUpdates.subscriptionCount()).isEqualTo(baseline)
        val path=temporary.newFolder().path;val started=CompletableDeferred<Unit>()
        val job=launch(Dispatchers.Default) { RepositoryUpdates.observe(path).collect { started.complete(Unit);awaitCancellation() } }
        started.await();assertThat(RepositoryUpdates.subscriptionCount()).isEqualTo(baseline+1)
        job.cancelAndJoin();assertThat(RepositoryUpdates.subscriptionCount()).isEqualTo(baseline)
        RepositoryUpdates.observe(path).first();assertThat(RepositoryUpdates.subscriptionCount()).isEqualTo(baseline)
    }
}
