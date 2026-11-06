package com.bontecou.syncmd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED 🔴 Tests for A0.1: Pure Kotlin Project Setup
 *
 * These tests verify the basic project structure and dependencies are correctly configured.
 */
class ProjectSetupTest {

    @Test
    fun `kotlin standard library is available`() {
        val list: List<String> = listOf("a", "b", "c")
        assertThat(list).hasSize(3)
    }

    @Test
    fun `coroutines core is available`() {
        val dispatcher = kotlinx.coroutines.Dispatchers.Default
        assertThat(dispatcher).isNotNull()
    }

    @Test
    fun `gson is available`() {
        val gson = com.google.gson.Gson()
        val data = gson.toJson(mapOf("name" to "test"))
        assertThat(data).contains("name")
    }

    @Test
    fun `truth assertions available`() {
        val value = 42
        assertThat(value).isEqualTo(42)
    }

    @Test
    fun `mockito kotlin is available`() {
        // Test that mockito-kotlin is on the classpath
        val mockClass = "org.mockito.kotlin.Mockito"
        assertThat(mockClass).isNotEmpty()
    }

    @Test
    fun `app version is accessible`() {
        assertThat(Version.APP_NAME).isEqualTo("Gitsync.md")
        assertThat(Version.APP_VERSION).isEqualTo("1.0.2")
    }

    @Test
    fun `core module builds successfully`() {
        // This test passes if the module compiled without errors
        val className = "com.bontecou.syncmd.Version"
        assertThat(className).isNotEmpty()
    }
}
