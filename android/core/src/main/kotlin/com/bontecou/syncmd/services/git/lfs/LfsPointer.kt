package com.bontecou.syncmd.services.git.lfs

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Canonical v1 SHA256 pointer. Unrecognized extension records are never silently discarded. */
data class LfsPointer(val oid: String, val size: Long) {
    init { require(oid.matches(Regex("[0-9a-f]{64}")) && size >= 0) { "Invalid Git LFS object identity" } }
    fun bytes() = "version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize $size\n".toByteArray(Charsets.UTF_8)
    companion object {
        const val MAX_BYTES = 2048
        fun parse(bytes: ByteArray): LfsPointer? {
            if (bytes.size > MAX_BYTES) return null
            val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
            val lines = text.removeSuffix("\n").split('\n')
            if (lines.size != 3 || lines[0] != "version https://git-lfs.github.com/spec/v1") return null
            if (!lines[1].matches(Regex("oid sha256:[0-9a-f]{64}")) || !lines[2].matches(Regex("size (0|[1-9][0-9]*)"))) return null
            val size = lines[2].removePrefix("size ").toLongOrNull() ?: return null
            return LfsPointer(lines[1].removePrefix("oid sha256:"), size)
        }
    }
}
