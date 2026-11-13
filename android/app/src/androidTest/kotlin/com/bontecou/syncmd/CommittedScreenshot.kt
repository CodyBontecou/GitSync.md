package com.bontecou.syncmd

import android.app.Instrumentation
import android.os.Build
import android.view.Choreographer
import android.view.View
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A dialog is a separate window; copying a Compose node can capture the activity below it. */
internal object CommittedScreenshot {
    fun save(instrumentation: Instrumentation, fallback: View, file: File) {
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = if (Build.VERSION.SDK_INT >= 29)
                android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { it.isShown && it.hasWindowFocus() }
                    ?: error("No focused app window for evidence") else fallback
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                view.postInvalidateOnAnimation()
            } else {
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback { committed.countDown() }
                    view.postInvalidateOnAnimation()
                }
                view.postInvalidateOnAnimation()
            }
        }
        check(committed.await(5, TimeUnit.SECONDS)) { "Evidence window frame did not commit" }
        file.outputStream().use {
            check(requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
