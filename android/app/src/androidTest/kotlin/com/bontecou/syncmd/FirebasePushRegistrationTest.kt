package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.push.FirebasePushPlatform
import com.bontecou.syncmd.push.PushStateStore
import com.bontecou.syncmd.push.PushRelayClient
import com.bontecou.syncmd.services.push.PushSyncProtocol
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/** Explicit opt-ins for personal Firebase, and separately for owned production relay registration. */
class FirebasePushRegistrationTest {
    @Test fun deployedRelayRegistersAndRemovesAnActualPersonalProjectAndroidToken() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRelay") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PushStateStore(context)
        require(!store.read().enabled && store.read().token == null)
        assertEquals("gitsync-md-android-20261028", FirebaseApp.getInstance().options.projectId)
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        val client = PushRelayClient()
        val deviceSecret = UUID.randomUUID().toString()
        val deviceBody = JSONObject().put("deviceSecret", deviceSecret).toString()
        try {
            val token = withTimeout(90_000) { FirebasePushPlatform(context).token() }
            val response = client.post("register", PushSyncProtocol.registration(token, deviceSecret, listOf("octocat/hello-world"), true))
            assertEquals(200, response.status); assertTrue(JSONObject(response.body).getBoolean("ok"))
            val status = client.post("github-app/status", deviceBody)
            assertEquals(200, status.status); assertEquals(emptyList<Any>(), PushSyncProtocol.installations(status.body))
        } finally {
            try {
                val removed = client.post("unregister", deviceBody)
                assertEquals(200, removed.status); assertTrue(JSONObject(removed.body).getBoolean("ok"))
                assertEquals(404, client.post("github-app/status", deviceBody).status)
            } finally {
                withTimeout(30_000) { suspendCancellableCoroutine<Unit> { continuation ->
                    FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                        if (continuation.isActive) {
                            if (task.isSuccessful) continuation.resume(Unit) else continuation.cancel(IllegalStateException("Test registration cleanup failed"))
                        }
                    }
                } }
            }
        }
        assertFalse(store.read().enabled); assertNull(store.read().token)
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
    }
    @Test fun approvedPersonalFirebaseProjectCanIssueAndDeleteAnActualAndroidRegistrationToken() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFirebase") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = PushStateStore(context).read()
        require(!before.enabled && before.token == null) { "This check requires the dedicated debug installation with Push Sync unused." }
        val app = FirebaseApp.getInstance()
        assertEquals("gitsync-md-android-20261028", app.options.projectId)
        val platform = FirebasePushPlatform(context); assertTrue(platform.configured())
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
        try {
            val token = withTimeout(90_000) { platform.token() }
            assertTrue(token.matches(Regex("[A-Za-z0-9_.:-]{32,4096}")))
            assertFalse(PushStateStore(context).read().enabled); assertNull(PushStateStore(context).read().token)
        } finally {
            withTimeout(30_000) { suspendCancellableCoroutine<Unit> { continuation ->
                FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                    if (continuation.isActive) {
                        if (task.isSuccessful) continuation.resume(Unit) else continuation.cancel(IllegalStateException("Test registration cleanup failed"))
                    }
                }
            } }
        }
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
    }
}
