package com.bontecou.syncmd.services.git

import java.io.File
import java.net.URI

/** Local URLs never carry network credentials or identify another machine. */
data class FileRemote private constructor(val url: String, val path: String) {
    companion object {
        fun parse(value: String): FileRemote {
            val text = value.trim()
            require(text.length in 1..16384 && text.none { it.isISOControl() || it == '\\' }) { "Enter an absolute local file URL" }
            val uri = URI(text)
            require(uri.scheme.equals("file", true) && !uri.isOpaque) { "Enter an absolute file:/// repository URL" }
            require(uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawAuthority.isNullOrEmpty() || uri.host.equals("localhost", true))) { "File URLs must identify a local folder without credentials or query parameters" }
            val path = uri.path.orEmpty()
            require(path.startsWith('/') && path.trim('/').isNotBlank() && path.split('/').none { it == "." || it == ".." } &&
                path.none { it.isISOControl() || it == '\\' }) { "Include an absolute repository path without traversal or control characters" }
            // URI decoding is appropriate for filesystem names: escaped #, ? and spaces remain part of the filename.
            val normalized = File(path).absolutePath.trimEnd('/')
            return FileRemote(URI("file", "", normalized, null).toASCIIString(), normalized)
        }
    }
}
