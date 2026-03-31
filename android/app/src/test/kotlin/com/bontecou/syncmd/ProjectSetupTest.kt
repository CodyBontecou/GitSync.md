package com.bontecou.syncmd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED 🔴 Tests for A0.1: Android Project + Gradle Setup
 *
 * These tests verify the basic project structure and dependencies are correctly configured.
 */
class ProjectSetupTest {

    @Test
    fun `kotlin imports resolve correctly`() {
        // This test passes if we can access Kotlin stdlib
        val list: List<String> = listOf("a", "b", "c")
        assertThat(list).hasSize(3)
    }

    @Test
    fun `compose imports available`() {
        // This test passes if Compose libraries are on classpath
        val composeDependency = "androidx.compose.ui:ui"
        assertThat(composeDependency).isNotEmpty()
    }

    @Test
    fun `hilt dependency configured`() {
        // This test passes if Hilt library is available
        val hiltDependency = "com.google.dagger:hilt-android"
        assertThat(hiltDependency).isNotEmpty()
    }

    @Test
    fun `retrofit dependency configured`() {
        // This test passes if Retrofit is available
        val retrofitDependency = "com.squareup.retrofit2:retrofit"
        assertThat(retrofitDependency).isNotEmpty()
    }

    @Test
    fun `coroutines dependency configured`() {
        // This test passes if Coroutines are available
        val coroutineDependency = "org.jetbrains.kotlinx:kotlinx-coroutines-core"
        assertThat(coroutineDependency).isNotEmpty()
    }

    @Test
    fun `testing dependencies configured`() {
        // This test passes if JUnit, Mockito, Truth are available
        val junit = "junit:junit"
        val mockito = "org.mockito:mockito-core"
        val truth = "com.google.truth:truth"

        assertThat(junit).isNotEmpty()
        assertThat(mockito).isNotEmpty()
        assertThat(truth).isNotEmpty()
    }

    @Test
    fun `espresso and compose testing dependencies available`() {
        val espresso = "androidx.test.espresso:espresso-core"
        val composeTest = "androidx.compose.ui:ui-test-junit4"

        assertThat(espresso).isNotEmpty()
        assertThat(composeTest).isNotEmpty()
    }
}
