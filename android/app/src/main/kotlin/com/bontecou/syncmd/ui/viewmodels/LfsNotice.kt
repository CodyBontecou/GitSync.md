package com.bontecou.syncmd.ui.viewmodels

/** Completed-operation data is retained independently of the language used to display it. */
sealed interface LfsNotice {
    data object TrackingUpdated : LfsNotice
    data object ExtensionsUpdated : LfsNotice
    data class Hydrated(val files: Int, val objects: Int) : LfsNotice
    data class LockListRefreshed(val supported: Boolean) : LfsNotice
    // Lock mutation and endpoint feedback remain explicit legacy text until their own typed localization pass.
    data class Plain(val text: String) : LfsNotice
}
