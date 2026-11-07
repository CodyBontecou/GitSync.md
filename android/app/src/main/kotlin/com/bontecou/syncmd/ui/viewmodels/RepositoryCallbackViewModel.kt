package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.automation.RepositoryCallbackIncoming
import com.bontecou.syncmd.automation.RepositoryCallbackGrantService
import com.bontecou.syncmd.automation.RepositoryCallbackGrant
import com.bontecou.syncmd.services.automation.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RepositoryCallbackViewModel(private val backend: RepositoryCallbackRunner,
    private val grants: RepositoryCallbackGrantService? = null, private val copyLink: (String) -> Unit = {}, private val deliver: (String) -> Unit) : ViewModel() {
    @Inject constructor(backend: RepositoryCallbackRunner, @ApplicationContext context: Context, grants: RepositoryCallbackGrantService) : this(backend, grants, { link ->
        val clip = android.content.ClipData.newPlainText("GitSync.md authorized link", link)
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
    }, { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    })
    enum class Phase { EMPTY, PREPARING, REVIEW, RUNNING, DONE }
    data class State(val id: String? = null, val request: RepositoryCallbackRequest? = null, val phase: Phase = Phase.EMPTY,
        val candidates: List<PullAllTarget> = emptyList(), val review: RepositoryCallbackReview? = null,
        val result: RepositoryActionResult? = null, val response: RepositoryCallbackResponse? = null,
        val rejectedIncoming: Boolean = false, val returned: Boolean = false, val deliveryFailed: Boolean = false,
        val authorized: Boolean = false, val automaticReturn: Boolean = false, val canIssue: Boolean = false,
        val issuing: Boolean = false, val grantCopied: Boolean = false, val grantFailed: Boolean = false,
        val issuedGrantId: String? = null, val authorizationRequested: Boolean = false) {
        val busy get() = phase == Phase.PREPARING || phase == Phase.RUNNING
        val destination get() = if (request != null && response != null) RepositoryCallbackProtocol.destination(request, response) else null
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var foreground = false
    fun foreground(value: Boolean) { foreground = value }
    fun automaticReturn(value: Boolean) { if (mutable.value.phase == Phase.REVIEW) mutable.value = mutable.value.copy(automaticReturn = value) }
    fun accept(incoming: RepositoryCallbackIncoming) {
        if (mutable.value.id == incoming.id) return
        val current = mutable.value
        if (current.phase != Phase.EMPTY && !current.returned && !(current.phase == Phase.DONE && incoming.authorization != null)) {
            mutable.value = mutable.value.copy(rejectedIncoming = true); return
        }
        job?.cancel()
        val request = incoming.request
        mutable.value = State(id = incoming.id, request = request, phase = Phase.PREPARING, canIssue = grants != null, authorizationRequested = incoming.authorization != null)
        incoming.authorization?.let { token -> acceptAuthorized(incoming.id, token); return }
        if (request == null || request.problem != null) {
            complete(incoming.id, RepositoryCallbackProtocol.failure(request, request?.problem ?: CallbackProblem.INVALID_REQUEST)); return
        }
        job = viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) { backend.candidates(request) }
                if (candidates.isEmpty()) complete(incoming.id, RepositoryCallbackProtocol.failure(request, CallbackProblem.NOT_FOUND))
                else {
                    val review = if (candidates.size == 1) backend.review(request, candidates.single()) else null
                    update(incoming.id) { it.copy(phase = Phase.REVIEW, candidates = candidates, review = review) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { complete(incoming.id, RepositoryCallbackProtocol.failure(request, CallbackProblem.FAILED)) }
        }
    }
    fun select(target: PullAllTarget) {
        val before = mutable.value
        if (before.phase != Phase.REVIEW || target !in before.candidates) return
        val request = before.request ?: return
        mutable.value = before.copy(phase = Phase.PREPARING, review = null)
        job = viewModelScope.launch {
            try {
                val review = backend.review(request, target)
                update(before.id!!) { it.copy(phase = Phase.REVIEW, review = review) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { complete(before.id!!, RepositoryCallbackProtocol.failure(request, CallbackProblem.CHANGED)) }
        }
    }
    fun runReviewed() {
        val before = mutable.value
        val review = before.review ?: return
        val request = before.request ?: return
        if (before.phase != Phase.REVIEW || before.issuing) return
        mutable.value = before.copy(phase = Phase.RUNNING)
        job = viewModelScope.launch {
            try {
                val result = backend.run(request, review)
                update(before.id!!) { it.copy(phase = Phase.DONE, result = result, response = RepositoryCallbackProtocol.response(request, result)) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { complete(before.id!!, RepositoryCallbackProtocol.failure(request, CallbackProblem.FAILED)) }
            automaticReturn(before.id!!, before.automaticReturn)
        }
    }
    fun issueLink(automaticReturn: Boolean, background: Boolean = false) {
        val before = mutable.value; val review = before.review ?: return; val service = grants ?: return
        if (before.phase != Phase.REVIEW || before.issuing) return
        mutable.value = before.copy(issuing = true, grantFailed = false, grantCopied = false, issuedGrantId = null)
        job = viewModelScope.launch {
            try {
                val issued = service.issue(review, automaticReturn, background)
                try { copyLink(issued.link) }
                catch (failed: Exception) { service.store.revoke(issued.grant.scope.id); throw failed }
                update(before.id!!) { it.copy(grantCopied = true, issuedGrantId = issued.grant.scope.id) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { update(before.id!!) { it.copy(grantFailed = true) }
            } finally { update(before.id!!) { it.copy(issuing = false) } }
        }
    }
    private fun acceptAuthorized(id: String, token: String) {
        job = viewModelScope.launch {
            var grant: RepositoryCallbackGrant? = null
            try {
                val service = requireNotNull(grants)
                val authorized = withContext(Dispatchers.IO) { service.store.authorized(token) }; grant = authorized
                update(id) { it.copy(request = authorized.request, authorized = true, automaticReturn = authorized.automaticReturn) }
                val result = service.run(token, authorized) { review ->
                    withContext(Dispatchers.Main.immediate) { update(id) { it.copy(review = review, phase = Phase.RUNNING) } }
                }
                update(id) { it.copy(phase = Phase.DONE, result = result, response = RepositoryCallbackProtocol.response(authorized.request, result)) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { complete(id, RepositoryCallbackProtocol.failure(mutable.value.request, CallbackProblem.CHANGED)) }
            val authorized = grant
            if (authorized != null && requireNotNull(grants).canReturn(token, authorized)) automaticReturn(id, authorized.automaticReturn)
        }
    }
    private fun automaticReturn(id: String, requested: Boolean) {
        if (requested && foreground && mutable.value.id == id) returnToCaller()
    }
    /** No automatic continuation after leaving the foreground, including while waiting for a directory lease. */
    fun cancel(background: Boolean = false) {
        val before = mutable.value
        if (before.phase !in setOf(Phase.PREPARING, Phase.REVIEW, Phase.RUNNING)) return
        job?.cancel()
        complete(before.id!!, RepositoryCallbackProtocol.failure(before.request,
            if (background && before.phase == Phase.RUNNING) CallbackProblem.INTERRUPTED else CallbackProblem.CANCELLED))
    }
    fun dismiss() { job?.cancel(); mutable.value = State() }
    fun returnToCaller() {
        val before = mutable.value
        if (before.phase != Phase.DONE || before.returned) return
        val destination = before.destination ?: return
        // Mark before launching: onStop/recreation cannot dispatch a second redirect.
        mutable.value = before.copy(returned = true, deliveryFailed = false)
        try { deliver(destination) }
        catch (_: Exception) { update(before.id!!) { it.copy(returned = false, deliveryFailed = true) } }
    }
    private fun complete(id: String, response: RepositoryCallbackResponse) = update(id) { it.copy(phase = Phase.DONE, response = response) }
    private fun update(id: String, change: (State) -> State) { if (mutable.value.id == id) mutable.value = change(mutable.value) }
}
