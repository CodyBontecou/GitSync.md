package com.bontecou.syncmd.ui.util

import android.content.Context
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import com.bontecou.syncmd.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiTextFailureTest {
    @Test fun `authored storage failure keeps resource identity until display`() {
        val failure = UiTextFailure(R.string.l10n_save_default_author)
        assertThat(UiText.of(failure, R.string.account_save_author_failed))
            .isEqualTo(UiText.res(R.string.l10n_save_default_author))
        assertThat(failure.message).isNull()
    }

    @Test fun `the same failure resolves using the context at display time`() {
        val text = UiText.of(UiTextFailure(R.string.l10n_save_default_author), R.string.account_save_author_failed)
        val first = mock<Context>()
        val second = mock<Context>()
        whenever(first.getString(R.string.l10n_save_default_author, *emptyArray<Any>())).thenReturn("No se pudo guardar el autor")
        whenever(second.getString(R.string.l10n_save_default_author, *emptyArray<Any>())).thenReturn("Der Autor konnte nicht gespeichert werden")
        assertThat(text.resolve(first)).isEqualTo("No se pudo guardar el autor")
        assertThat(text.resolve(second)).isEqualTo("Der Autor konnte nicht gespeichert werden")
    }

    @Test fun `Git output stays verbatim and empty failures use localized fallback`() {
        assertThat(UiText.of(IllegalStateException("remote: rejected"), R.string.account_save_author_failed))
            .isEqualTo(UiText.Raw("remote: rejected"))
        assertThat(UiText.of(IllegalStateException(), R.string.account_save_author_failed))
            .isEqualTo(UiText.res(R.string.account_save_author_failed))
    }
}
