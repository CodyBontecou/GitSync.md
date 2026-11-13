package com.bontecou.syncmd.services.automation

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class CallbackProblem { INVALID_REQUEST, UNKNOWN_ACTION, MISSING_REPOSITORY, NOT_FOUND, AMBIGUOUS, CHANGED, BUSY, CANCELLED, INTERRUPTED, NO_CHANGES, FAILED }
data class RepositoryCallbackRequest(val actionName: String, val action: RepositoryAction?, val repository: String?,
    val message: String, val success: String?, val error: String?, val problem: CallbackProblem? = null)
data class RepositoryCallbackReview(val plan: RepositoryActionPlan, val account: String, val request: RepositoryCallbackRequest)
data class RepositoryCallbackResponse(val values: Map<String, String>) {
    val success get() = values["status"] == "ok"
}

/** x-callback-url's wire format. No Android intents, credentials, paths or exception text in responses. */
object RepositoryCallbackProtocol {
    private val responseKeys = setOf("action", "status", "message", "errorCode", "branch", "sha", "changes", "updated", "pull_updated", "push_skipped", "commit_saved")
    private val deniedSchemes = setOf("syncmd", "intent", "android-app", "javascript", "data", "file", "content", "tel", "sms", "smsto", "mms", "mmsto", "mailto", "market", "geo", "blob", "about", "chrome", "chrome-native", "android.resource")
    fun parse(raw: String): RepositoryCallbackRequest {
        require(raw.length <= 131072 && raw.none { it.code < 32 || it.code == 127 })
        val uri = URI(raw)
        require(uri.scheme == "syncmd" && uri.rawAuthority == "x-callback-url" && uri.rawFragment == null)
        val values = linkedMapOf<String, String>()
        uri.rawQuery?.split('&')?.forEach { part ->
            require(part.isNotEmpty() && '=' in part)
            val key = decode(part.substringBefore('=')); val value = decode(part.substringAfter('='))
            require(key !in values && key in setOf("repo", "message", "x-success", "x-error", "x-source"))
            require('\u0000' !in value)
            values[key] = value
        }
        val name = decode(uri.rawPath.orEmpty()).trim('/')
        val action = when (name) { "status" -> RepositoryAction.STATUS; "pull" -> RepositoryAction.PULL; "push" -> RepositoryAction.PUSH; "sync" -> RepositoryAction.SYNC; else -> null }
        val repository = values["repo"]?.takeIf { it.isNotBlank() }
        require(repository == null || (repository.length <= 4096 && repository.none { it == '/' || it == '\\' || it.code < 32 }))
        val message = values["message"].orEmpty().ifBlank { "Update from GitSync.md" }
        require(message.length <= 65536)
        val success = values["x-success"]?.also(::requireReturnUrl)
        val error = values["x-error"]?.also(::requireReturnUrl)
        return RepositoryCallbackRequest(name.take(64), action, repository, message, success, error,
            if (action == null) CallbackProblem.UNKNOWN_ACTION else if (repository == null) CallbackProblem.MISSING_REPOSITORY else null)
    }
    fun requireReturnUrl(raw: String) {
        require(raw.length in 1..32768 && raw.none { it.code < 32 || it.code == 127 })
        val uri = URI(raw); val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: error("Missing return scheme")
        require(!uri.isOpaque && scheme.length <= 64 && scheme !in deniedSchemes && uri.rawUserInfo == null)
        require(uri.port == -1 || uri.port in 1..65535)
        if (scheme in setOf("http", "https")) require(!uri.host.isNullOrBlank())
        uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach { part -> decode(part.substringBefore('=')); decode(part.substringAfter('=', "")) }
    }
    fun requestUrl(request: RepositoryCallbackRequest): String {
        require(request.problem == null && request.action != null && request.repository != null)
        val query = linkedMapOf("repo" to request.repository, "message" to request.message)
        request.success?.let { query["x-success"] = it }; request.error?.let { query["x-error"] = it }
        return "syncmd://x-callback-url/${request.actionName}?" + query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }
    fun failure(request: RepositoryCallbackRequest?, problem: CallbackProblem) = RepositoryCallbackResponse(buildMap {
        request?.actionName?.takeIf { it.isNotEmpty() }?.let { put("action", it) }
        put("status", "error"); put("errorCode", problem.name.lowercase(Locale.ROOT)); put("message", when (problem) {
            CallbackProblem.CANCELLED -> "Cancelled"
            CallbackProblem.INTERRUPTED -> "The action stopped. It may have finished; review the repository before retrying."
            CallbackProblem.NO_CHANGES -> "No changes to push"
            CallbackProblem.CHANGED -> "The reviewed repository or account changed. Send a new request."
            CallbackProblem.NOT_FOUND -> "Repository not found or not cloned"
            CallbackProblem.AMBIGUOUS -> "Choose the intended repository"
            CallbackProblem.BUSY -> "Another action is running. This request was not queued."
            CallbackProblem.MISSING_REPOSITORY -> "Missing required repo parameter"
            CallbackProblem.UNKNOWN_ACTION -> "Unknown action"
            CallbackProblem.INVALID_REQUEST -> "The callback request is invalid"
            CallbackProblem.FAILED -> "The action needs attention. Open GitSync.md to review the repository."
        })
    })
    fun response(request: RepositoryCallbackRequest, result: RepositoryActionResult): RepositoryCallbackResponse {
        require(request.action == result.action)
        val ok = when (result.outcome) {
            RepositoryActionOutcome.STATUS, RepositoryActionOutcome.PULLED, RepositoryActionOutcome.PUSHED,
            RepositoryActionOutcome.PULLED_AND_PUSHED, RepositoryActionOutcome.UP_TO_DATE -> true
            RepositoryActionOutcome.NO_CHANGES -> result.action == RepositoryAction.SYNC
            else -> false
        }
        val base = if (ok) mapOf("status" to "ok", "action" to request.actionName) else failure(request,
            if (result.outcome == RepositoryActionOutcome.NO_CHANGES) CallbackProblem.NO_CHANGES else CallbackProblem.FAILED).values
        return RepositoryCallbackResponse(base + buildMap {
            result.head?.let { put("sha", it) }
            if (result.action == RepositoryAction.STATUS) {
                result.branch?.let { put("branch", it) }; result.changes?.let { put("changes", "$it") }
            }
            if (result.action == RepositoryAction.PULL) put("updated", "${result.pullUpdated}")
            if (result.action == RepositoryAction.SYNC) {
                put("pull_updated", "${result.pullUpdated}")
                if (result.outcome in setOf(RepositoryActionOutcome.NO_CHANGES, RepositoryActionOutcome.PULLED)) put("push_skipped", "true")
            }
            if (result.outcome == RepositoryActionOutcome.LOCAL_COMMIT_SAVED) put("commit_saved", "true")
        })
    }
    fun destination(request: RepositoryCallbackRequest, response: RepositoryCallbackResponse): String? {
        val base = (if (response.success) request.success else request.error ?: request.success) ?: return null
        requireReturnUrl(base)
        val uri = URI(base)
        val retained = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() && decode(it.substringBefore('=')) !in responseKeys }.orEmpty()
        val query = (retained + response.values.map { "${encode(it.key)}=${encode(it.value)}" }).joinToString("&")
        return base.substringBefore('#').substringBefore('?') + "?" + query + (uri.rawFragment?.let { "#$it" } ?: "")
    }
    /** Display the destination without caller-provided query secrets or fragments. */
    fun displayDestination(raw: String): String = URI(raw).let { "${it.scheme}://${it.rawAuthority.orEmpty()}${it.rawPath.orEmpty()}" }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun decode(value: String): String {
        val out = ByteArrayOutputStream(); var i = 0
        while (i < value.length) {
            if (value[i] == '%') { require(i + 2 < value.length); val byte = value.substring(i + 1, i + 3).toIntOrNull(16); require(byte != null); out.write(byte); i += 3 }
            else { val end = value.indexOf('%', i).takeIf { it >= 0 } ?: value.length; out.write(value.substring(i, end).toByteArray(Charsets.UTF_8)); i = end }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray())).toString()
    }
}

/** Preparation is read-only. The selected target and account are revalidated inside the execution lease. */
class RepositoryCallbackRunner(private val runner: RepositoryActionRunner, private val catalog: () -> List<PullAllTarget>,
    private val account: (String) -> String) {
    fun candidates(request: RepositoryCallbackRequest): List<PullAllTarget> = catalog().filter { File(it.path).name == request.repository }
    suspend fun review(request: RepositoryCallbackRequest, target: PullAllTarget): RepositoryCallbackReview =
        RepositoryOperationCoordinator.withRepository(target.path) {
            require(request.problem == null && request.action != null && candidates(request).any { it == target })
            RepositoryCallbackReview(runner.review(target), account(target.path), request)
        }
    suspend fun refreshAuthorization(review: RepositoryCallbackReview): RepositoryCallbackReview =
        RepositoryOperationCoordinator.withRepository(review.plan.target.path) {
            val target = candidates(review.request).single { it.path == review.plan.target.path && it.registration == review.plan.target.registration }
            val fresh = this.review(review.request, target)
            require(fresh.account == review.account && fresh.plan.copy(head = null, target = fresh.plan.target.copy(name = review.plan.target.name)) == review.plan.copy(head = null))
            fresh
        }
    suspend fun run(request: RepositoryCallbackRequest, review: RepositoryCallbackReview, authorize: () -> Unit = {}): RepositoryActionResult =
        RepositoryOperationCoordinator.withRepository(review.plan.target.path) {
            require(review.request == request && request.problem == null && request.action != null && File(review.plan.target.path).name == request.repository)
            runner.run(review.plan, request.action, request.message) {
                authorize()
                require(account(review.plan.target.path) == review.account)
                require(candidates(request).any { it.path == review.plan.target.path && it.registration == review.plan.target.registration })
            }
        }
}
