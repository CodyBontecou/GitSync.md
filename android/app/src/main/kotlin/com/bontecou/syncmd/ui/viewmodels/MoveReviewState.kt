package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.storage.RepositoryMoveReview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/** Atomic screen/request ownership. Accepted filesystem work survives its screen; navigation does not. */
internal class MoveReviewState {
    private val lock = Any()
    private var screen: Any? = null
    private var route: String? = null
    private var executingRoute: String? = null
    private var request: Any = Any()
    private var executing: RepositoryMoveReview? = null
    private var completed: RepositoryMoveReview? = null
    private val mutableReview = MutableStateFlow<RepositoryMoveReview?>(null)
    val review = mutableReview.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    data class Completion(val routeId: String, val review: RepositoryMoveReview)
    private val mutableCompletion = MutableStateFlow<Completion?>(null)
    val completion = mutableCompletion.asStateFlow()

    fun open(owner: Any, routeId: String = java.util.UUID.randomUUID().toString()) = synchronized(lock) {
        if (route != routeId) mutableCompletion.value = null
        route = routeId
        screen = owner
        invalidatePending()
    }
    fun close(owner: Any) = synchronized(lock) {
        if (screen === owner) {
            screen = null
            invalidatePending()
        }
    }
    fun begin(owner: Any? = null): Any? = synchronized(lock) {
        if (executing != null || (owner != null && screen !== owner)) return@synchronized null
        invalidatePending()
        mutableCompletion.value = null
        request
    }
    fun isRequestCurrent(token: Any): Boolean = synchronized(lock) { request === token && executing == null }
    fun publish(token: Any, value: RepositoryMoveReview): Boolean = synchronized(lock) {
        if (request !== token || executing != null) return@synchronized false
        mutableReview.value = value
        mutableError.value = null
        true
    }
    fun fail(token: Any, message: String): Boolean = synchronized(lock) {
        if (request !== token || executing != null) return@synchronized false
        mutableError.value = message
        true
    }
    fun clear(owner: Any? = null) = synchronized(lock) {
        if (owner == null || screen === owner) {
            invalidatePending()
            mutableCompletion.value = null
        }
    }
    fun claim(value: RepositoryMoveReview, owner: Any? = null): Boolean = synchronized(lock) {
        if (executing != null || mutableReview.value !== value || (owner != null && screen !== owner)) return@synchronized false
        executing = value
        completed = null
        executingRoute = if (owner == null) null else route
        mutableCompletion.value = null
        request = Any()
        true
    }
    fun finish(value: RepositoryMoveReview) = synchronized(lock) {
        if (executing === value) { executing = null; executingRoute = null }
        if (mutableReview.value === value) mutableReview.value = null
    }
    fun complete(value: RepositoryMoveReview): Boolean = synchronized(lock) {
        val target = executingRoute
        if (executing !== value || completed === value || target == null || route != target) return@synchronized false
        completed = value
        mutableCompletion.value = Completion(target, value)
        true
    }
    /** Called synchronously by a resumed screen; consuming the exact receipt prevents replay. */
    fun consume(owner: Any, value: Completion): Boolean = synchronized(lock) {
        if (screen !== owner || route != value.routeId || mutableCompletion.value !== value) return@synchronized false
        mutableCompletion.value = null
        true
    }
    suspend fun deliver(owner: Any, lifecycle: Lifecycle, onReady: () -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            completion.collect { value ->
                currentCoroutineContext().ensureActive()
                if (value != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && consume(owner, value)) onReady()
            }
        }
    }
    fun ownsScreen(owner: Any): Boolean = synchronized(lock) { screen === owner }
    private fun invalidatePending() {
        request = Any()
        mutableError.value = null
        if (executing == null) mutableReview.value = null
    }
}
