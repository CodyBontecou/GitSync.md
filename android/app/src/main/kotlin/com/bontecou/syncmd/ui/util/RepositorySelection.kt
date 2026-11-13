package com.bontecou.syncmd.ui.util

/** Identity changes on every repository switch, including A → B → A. Keep it with UI callbacks. */
class RepositorySelection internal constructor(val path: String)
