package com.bontecou.syncmd.ui.util

/** Keep pasted names on one visible line without silently hiding submitted characters. */
internal fun singleLineNameInput(value: String): String = buildString {
    value.replace("\r\n", "\n").forEach { character ->
        append(if (character.isISOControl() || character == '\u2028' || character == '\u2029') ' ' else character)
    }
}
