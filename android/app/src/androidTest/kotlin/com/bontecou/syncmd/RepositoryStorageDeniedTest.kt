package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.storage.RepositoryLocations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run in a fresh process after revoking the app-op; Android terminates running apps on that change. */
class RepositoryStorageDeniedTest {
    @Test fun deniedSharedStorageStopsRepositoryOperationsButAppStorageRemainsAvailable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Revoke MANAGE_EXTERNAL_STORAGE before this separate test run", RepositoryLocations.hasSharedStorageAccess(context))
        val locations = RepositoryLocations(context)
        val path = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "gitsync-denied-fixture").path
        var touched = false
        val result = runCatching { RepositoryOperationCoordinator.withRepository(path) { touched = true } }
        assertTrue(result.isFailure)
        assertFalse(touched)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("Shared storage access"))
        val appFolder = locations.resolve(RepositoryLocations.APP)
        RepositoryOperationCoordinator.withRepository(appFolder.path) { touched = true }
        assertTrue(touched)
        touched = false
        RepositoryOperationCoordinator.withRepository(File(context.cacheDir, "permission-probe").path) { touched = true }
        assertTrue(touched)
    }
}
