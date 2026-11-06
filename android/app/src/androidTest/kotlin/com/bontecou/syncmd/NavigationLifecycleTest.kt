package com.bontecou.syncmd

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.bontecou.syncmd.ui.util.awaitResumedNavigationEntry
import com.bontecou.syncmd.ui.navigateSafe
import com.bontecou.syncmd.ui.popBackStackSafe
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.junit.Test
import org.junit.Assert.*

/** Deterministic lifecycle reentrancy probe. Its synthetic lifecycle never touches app preferences. */
class NavigationLifecycleTest {
    @get:org.junit.Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private val owned = mutableListOf<Owner>()
    @org.junit.After fun destroyOwnedLifecycles() {
        compose.runOnUiThread { owned.forEach { if (it.registry.currentState != Lifecycle.State.DESTROYED) it.registry.currentState = Lifecycle.State.DESTROYED } }
    }
    private fun startedHost(owner: Owner): NavHostController {
        owned += owner
        lateinit var controller: NavHostController
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                controller = rememberNavController()
                NavHost(controller, startDestination = "home") {
                    composable("home") { androidx.compose.material3.Text("Queued home") }
                    composable("incoming") { androidx.compose.material3.Text("Queued incoming") }
                    composable("replacement") { androidx.compose.material3.Text("Queued replacement") }
                }
            }
        }
        compose.waitUntil(10_000) { controller.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.STARTED }
        return controller
    }
    @Test fun aForwardTapDuringTransitionRunsAfterItsSourceResumes() {
        val owner = Owner(); val navigation = startedHost(owner)
        compose.runOnUiThread { navigation.navigateSafe("incoming"); assertEquals("home", navigation.currentDestination?.route) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { navigation.currentDestination?.route == "incoming" }
        compose.onNodeWithText("Queued incoming").assertIsDisplayed()
    }
    @Test fun duplicateQueuedTapsCannotCreateTwoCopiesOfTheDestination() {
        val owner = Owner(); val navigation = startedHost(owner)
        compose.runOnUiThread { navigation.navigateSafe("incoming"); navigation.navigateSafe("incoming"); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { navigation.currentDestination?.route == "incoming" && navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        compose.runOnUiThread { assertTrue(navigation.popBackStackSafe()) }
        compose.waitUntil(10_000) { navigation.currentDestination?.route == "home" }
        compose.onNodeWithText("Queued home").assertIsDisplayed()
    }
    @Test fun queuedNavigationCannotReplayAfterItsSourceWasLeftAndReturnedTo() {
        val owner = Owner(); val navigation = startedHost(owner)
        compose.runOnUiThread { navigation.navigateSafe("incoming"); navigation.navigate("replacement") }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { navigation.currentDestination?.route == "replacement" && navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        compose.runOnUiThread { assertTrue(navigation.popBackStack()) }
        compose.waitUntil(10_000) { navigation.currentDestination?.route == "home" && navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        compose.waitForIdle(); compose.onNodeWithText("Queued home").assertIsDisplayed(); compose.onNodeWithText("Queued incoming").assertDoesNotExist()
        compose.runOnUiThread { navigation.navigateSafe("incoming"); navigation.navigate("replacement"); navigation.popBackStack() }
        compose.waitForIdle(); compose.onNodeWithText("Queued home").assertIsDisplayed(); compose.onNodeWithText("Queued incoming").assertDoesNotExist()
    }
    @Test fun aBackTapDuringTransitionIsAcceptedAndPopsWhenItsSourceResumes() {
        val owner = Owner(); val navigation = startedHost(owner)
        compose.runOnUiThread { navigation.navigate("incoming") }
        compose.waitUntil(10_000) { navigation.currentDestination?.route == "incoming" && navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.STARTED }
        compose.runOnUiThread { assertTrue(navigation.popBackStackSafe()); assertEquals("incoming", navigation.currentDestination?.route); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { navigation.currentDestination?.route == "home" }
        compose.onNodeWithText("Queued home").assertIsDisplayed()
    }
    @Test fun incomingNavigationCannotMutateTheBackStackInsideLifecycleDispatch() {
        val owner = Owner(); lateinit var navigation: NavHostController
        var started = false; var finished = false; var dispatchError: Throwable? = null
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                navigation = rememberNavController()
                NavHost(navigation, startDestination = "home") {
                    composable("home") { androidx.compose.material3.Text("Lifecycle home") }
                    composable("incoming") { androidx.compose.material3.Text("Incoming destination") }
                }
                LaunchedEffect(Unit) {
                    started = true
                    navigation.awaitResumedNavigationEntry()
                    navigation.navigate("incoming")
                    finished = true
                }
            }
        }
        compose.waitUntil(10_000) { started && navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.CREATED }
        compose.runOnUiThread { dispatchError = runCatching { owner.registry.currentState = Lifecycle.State.RESUMED }.exceptionOrNull() }
        assertNull("Lifecycle dispatch must complete before an incoming navigation changes the back stack", dispatchError)
        compose.waitUntil(10_000) { finished }
        compose.onNodeWithText("Incoming destination").assertIsDisplayed()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    @Test fun cancellingDuringResumeRemovesPostedNavigationBeforeItCanRun() {
        val owner = Owner(); lateinit var navigation: NavHostController; var caller: Job? = null
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                navigation = rememberNavController()
                NavHost(navigation, startDestination = "home") {
                    composable("home") { androidx.compose.material3.Text("Cancellation home") }
                    composable("incoming") { androidx.compose.material3.Text("Must not open") }
                }
                LaunchedEffect(Unit) {
                    caller = currentCoroutineContext().job
                    navigation.awaitResumedNavigationEntry(); navigation.navigate("incoming")
                }
            }
        }
        compose.waitUntil(10_000) { caller != null && navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.CREATED }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED; caller!!.cancel() }
        compose.waitForIdle(); compose.onNodeWithText("Cancellation home").assertIsDisplayed(); compose.onNodeWithText("Must not open").assertDoesNotExist()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    @Test fun aDestroyedDestinationDoesNotSpinOrAuthorizeNavigationForAnOldEntry() {
        val owner = Owner(); lateinit var navigation: NavHostController; var started = false; var selected: String? = null
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                navigation = rememberNavController()
                NavHost(navigation, startDestination = "home") {
                    composable("home") { androidx.compose.material3.Text("Old destination") }
                    composable("replacement") { androidx.compose.material3.Text("Replacement destination") }
                }
                LaunchedEffect(Unit) { started = true; selected = navigation.awaitResumedNavigationEntry().destination.route }
            }
        }
        compose.waitUntil(10_000) { started && navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.CREATED }
        compose.runOnUiThread { navigation.navigate("replacement") { popUpTo("home") { inclusive = true } } }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { selected != null }; assertEquals("replacement", selected)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
}
