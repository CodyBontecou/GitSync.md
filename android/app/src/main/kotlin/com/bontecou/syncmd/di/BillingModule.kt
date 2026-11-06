package com.bontecou.syncmd.di

import android.content.Context
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.billing.AppUnlock
import com.bontecou.syncmd.billing.PlayBillingGateway
import com.bontecou.syncmd.billing.PrefsUnlockStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    @Provides
    @Singleton
    fun provideAppUnlock(@ApplicationContext context: Context): AppUnlock = AppUnlock(
        PlayBillingGateway(context), PrefsUnlockStore(context), BuildConfig.HARD_PAYWALL,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}
