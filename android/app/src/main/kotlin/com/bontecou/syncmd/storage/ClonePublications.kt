package com.bontecou.syncmd.storage

import android.content.Context
import com.bontecou.syncmd.services.repositories.ClonePublicationLedger
import com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry
import com.google.gson.Gson
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

fun clonePublications(context: Context) = ClonePublicationLedger(File(context.noBackupFilesDir, "clone-publications").canonicalFile)

/** Preserve history ownership without copying a legacy remote URL or credential into a journal. */
fun cloneHistoryReview(entry: RepositoryHistoryEntry): String = JSONObject().put("path", entry.repository.path)
    .put("fingerprint", MessageDigest.getInstance("SHA-256").digest(Gson().toJson(entry).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }).toString()
