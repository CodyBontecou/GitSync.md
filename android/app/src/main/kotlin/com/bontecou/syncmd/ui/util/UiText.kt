package com.bontecou.syncmd.ui.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * User-visible copy produced outside Compose by classes that have no Context.
 * Resources resolve in the current locale at display time; [Raw] carries data
 * such as Git or server output that is shown verbatim.
 */
sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val quantity: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Resource -> context.getString(id, *resolved(context, args))
        is Plural -> context.resources.getQuantityString(id, quantity, *resolved(context, args))
        is Raw -> value
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Resource(id, args.toList())
        fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): UiText = Plural(id, quantity, args.toList())
        /** An exception's own message, or [fallback] when it has none. */
        fun of(error: Throwable?, @StringRes fallback: Int): UiText =
            error?.message?.takeIf { it.isNotBlank() }?.let(::Raw) ?: Resource(fallback)
    }
}

private fun resolved(context: Context, args: List<Any>): Array<Any> =
    args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
