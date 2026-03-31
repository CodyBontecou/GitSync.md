package com.bontecou.syncmd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RED 🔴 Instrumented tests for A0.1: Hilt initialization and app context
 */
@RunWith(AndroidJUnit4::class)
class HiltInitializationTest {

    @Test
    fun `app context is available`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context).isNotNull()
    }

    @Test
    fun `app is SyncMdApp instance`() {
        val app = ApplicationProvider.getApplicationContext<SyncMdApp>()
        assertThat(app).isNotNull()
        assertThat(app).isInstanceOf(SyncMdApp::class.java)
    }

    @Test
    fun `app package name is correct`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.packageName).isEqualTo("com.bontecou.syncmd")
    }
}
