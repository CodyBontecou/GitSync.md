package com.bontecou.syncmd.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CountedPhraseTest {
    @Test fun `a metric value is rendered once while keeping its own style`() {
        val phrase = countedPhrase("%1\$s changes", "21")
        assertThat(phrase.value).isEqualTo("21")
        assertThat(phrase.label).isEqualTo("changes")
        assertThat(phrase.text).isEqualTo("21 changes")
    }

    @Test fun `translated value placement and RTL digits remain intact`() {
        val before = countedPhrase("Files: %1\$s", "٢")
        assertThat(before.before).isEqualTo("Files: ")
        assertThat(before.text).isEqualTo("Files: ٢")
        assertThat(countedPhrase("%1\$s تغيير", "٢").text).isEqualTo("٢ تغيير")
    }

    @Test fun `a full localized subject can retain accent styling separately`() {
        val phrase = countedPhrase("%1\$s need you.", "21 vaults")
        assertThat(phrase.value).isEqualTo("21 vaults")
        assertThat(phrase.after).isEqualTo(" need you.")
        assertThat(phrase.text).isEqualTo("21 vaults need you.")
    }

    @Test fun `missing or duplicated value placeholders are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { countedPhrase("changes", "21") }
        assertThrows(IllegalArgumentException::class.java) { countedPhrase("%1\$s + %1\$s", "21") }
    }
}
