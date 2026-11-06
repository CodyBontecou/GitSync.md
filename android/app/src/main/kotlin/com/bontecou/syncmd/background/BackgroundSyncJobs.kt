package com.bontecou.syncmd.background

import android.content.Context
import androidx.work.*
import com.bontecou.syncmd.push.PushRuntime
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@EntryPoint @InstallIn(SingletonComponent::class)
interface BackgroundSyncEntryPoint { fun background(): BackgroundSyncBackend }
object BackgroundSyncJobs {
    const val PERIODIC = "background-sync-periodic"
    const val NOW = "background-sync-now"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        val active = runCatching { BackgroundSyncStore(context).read().active }.getOrDefault(false)
        if (!active) { work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(NOW); return }
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES).setInitialDelay(15, TimeUnit.MINUTES).setConstraints(constraints()).build())
    }
    fun now(context: Context) {
        require(BackgroundSyncStore(context).read().active)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackgroundSyncWorker>().setConstraints(constraints()).build())
    }
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        schedule(context)
    }
    fun preferencesChanged(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(NOW)
        schedule(context)
    }
}
class BackgroundSyncWorker @JvmOverloads constructor(context: Context, parameters: WorkerParameters,
    private val overrideBackend: BackgroundSyncBackend? = null) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        PushRuntime.ready.await()
        val backend = overrideBackend ?: EntryPointAccessors.fromApplication(applicationContext, BackgroundSyncEntryPoint::class.java).background()
        backend.runAndRecord(BackgroundRunHistory(applicationContext)); Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.failure() }
}
