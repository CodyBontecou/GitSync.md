package com.bontecou.syncmd.diagnostics

import android.content.Context
import com.bontecou.syncmd.services.diagnostics.DiagnosticStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppDiagnostics private constructor(val store: DiagnosticStore) {
    @Inject constructor(@ApplicationContext context: Context) : this(DiagnosticStore(File(context.noBackupFilesDir, "diagnostics").canonicalFile))
    constructor(directory: File) : this(DiagnosticStore(directory.canonicalFile))
}
