package com.bontecou.syncmd.security

import com.bontecou.syncmd.ui.util.UiTextFailure
import com.bontecou.syncmd.R
import android.content.Context
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The app-wide "Commit as" author (Settings › New vaults). Null means "use the vault's GitHub account". */
data class DefaultAuthor(val name: String, val email: String)

/** Where [RepositoryAuthors] finds the global default; a vault's own `.git/config` author always wins. */
interface DefaultAuthorSource {
    val author: StateFlow<DefaultAuthor?>
}

/** Stores the global default author in private app preferences (never in a repository). */
@Singleton
class DefaultAuthorStore internal constructor(private val storage: Storage) : DefaultAuthorSource {
    interface Storage {
        fun read(): Pair<String?, String?>
        fun write(name: String?, email: String?): Boolean
    }

    @Inject constructor(@ApplicationContext context: Context) : this(PreferencesStorage(context))

    private val mutable = MutableStateFlow(decode(storage.read()))
    override val author: StateFlow<DefaultAuthor?> = mutable.asStateFlow()

    /** Validates like a repository author; throws IllegalArgumentException with a user-facing message. */
    fun save(name: String, email: String) {
        val (n, e) = RepositoryIdentity.validated(name, email)
        if (!storage.write(n, e)) throw UiTextFailure(R.string.l10n_save_default_author)
        mutable.value = DefaultAuthor(n, e)
    }

    /** Back to "From your GitHub account". */
    fun clear() {
        if (!storage.write(null, null)) throw UiTextFailure(R.string.l10n_save_default_author)
        mutable.value = null
    }

    private fun decode(stored: Pair<String?, String?>): DefaultAuthor? {
        val (name, email) = stored
        if (name.isNullOrBlank() || email.isNullOrBlank()) return null
        return runCatching { RepositoryIdentity.validated(name, email) }.getOrNull()?.let { DefaultAuthor(it.first, it.second) }
    }

    private class PreferencesStorage(context: Context) : Storage {
        private val prefs = context.getSharedPreferences("default_author", Context.MODE_PRIVATE)
        override fun read() = prefs.getString("name", null) to prefs.getString("email", null)
        override fun write(name: String?, email: String?) = prefs.edit().apply {
            if (name == null) remove("name") else putString("name", name)
            if (email == null) remove("email") else putString("email", email)
        }.commit()
    }

    companion object {
        /** No global default (tests and previews). */
        val None: DefaultAuthorSource = object : DefaultAuthorSource { override val author: StateFlow<DefaultAuthor?> = MutableStateFlow(null) }
    }
}
