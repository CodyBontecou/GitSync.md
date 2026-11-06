package com.bontecou.syncmd.ui.util

/** Extended grapheme clusters, matching Swift's String.count rather than UTF-16 units. */
private val graphemeCluster = Regex("\\X")

/** User-perceived characters: an emoji ZWJ sequence, a flag or a letter with combining marks counts once; CRLF counts once. */
fun userCharacterCount(text: String): Int = if (text.isEmpty()) 0 else graphemeCluster.findAll(text).count()
