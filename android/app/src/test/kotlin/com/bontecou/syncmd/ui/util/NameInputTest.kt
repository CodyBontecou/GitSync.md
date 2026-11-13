package com.bontecou.syncmd.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameInputTest {
    @Test fun `pasted controls become visible spaces and ordinary Unicode names are preserved`() {
        assertThat(singleLineNameInput("qa\r\nedge\ttest")).isEqualTo("qa edge test")
        assertThat(singleLineNameInput("a\u0000b\u007fc\u2028d\u2029e")).isEqualTo("a b c d e")
        assertThat(singleLineNameInput("دفتر café 🌱/draft-1")).isEqualTo("دفتر café 🌱/draft-1")
    }
}
