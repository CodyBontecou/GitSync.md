package com.bontecou.syncmd.automation

import com.bontecou.syncmd.services.automation.RepositoryCallbackProtocol
import com.bontecou.syncmd.services.automation.RepositoryCallbackRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class RepositoryCallbackIncoming(val id: String = UUID.randomUUID().toString(), val request: RepositoryCallbackRequest?, val authorization: String? = null) {
    override fun toString() = "RepositoryCallbackIncoming(id=$id, authorized=${authorization != null})"
}

/** Process-memory delivery only. Intent data is scrubbed; process restoration cannot repeat Git actions. */
object RepositoryCallbackRequests {
    private val mutable = MutableStateFlow<RepositoryCallbackIncoming?>(null)
    val pending = mutable.asStateFlow()
    fun offer(raw: String) {
        val request = runCatching { RepositoryCallbackProtocol.parse(raw) }.getOrNull()
        mutable.compareAndSet(null, RepositoryCallbackIncoming(request = request))
    }
    fun offerAuthorized(raw: String) {
        val token = runCatching { RepositoryCallbackGrantStore.tokenFromLink(raw) }.getOrNull()
        mutable.compareAndSet(null, RepositoryCallbackIncoming(request = null, authorization = token))
    }
    fun consume(incoming: RepositoryCallbackIncoming) = mutable.compareAndSet(incoming, null)
    fun clear() { mutable.value = null }
}

/** Bearer links never become MainActivity's task/base intent or navigation arguments. */
class AuthorizedCallbackActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data; intent.data = null
        val fresh = savedInstanceState == null && intent.action == android.content.Intent.ACTION_VIEW && data != null &&
            intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        val token = data?.let { runCatching { RepositoryCallbackGrantStore.tokenFromLink(it.toString()) }.getOrNull() }
        val grant = token?.let { runCatching { RepositoryCallbackGrantStore(this).authorized(it) }.getOrNull() }
        if (grant?.scope?.background == true) {
            if (!fresh) { finish(); return }
            try {
                val result = BackgroundCallbackDelivery.start(this, requireNotNull(token), grant)
                if (!result.dispatched) android.widget.Toast.makeText(this, com.bontecou.syncmd.R.string.callback_background_attention, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, com.bontecou.syncmd.R.string.callback_background_attention, android.widget.Toast.LENGTH_LONG).show()
            }
            finish(); return
        }
        if (fresh) RepositoryCallbackRequests.offerAuthorized(data.toString())
        startActivity(android.content.Intent(this, com.bontecou.syncmd.MainActivity::class.java).setAction(android.content.Intent.ACTION_MAIN)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
