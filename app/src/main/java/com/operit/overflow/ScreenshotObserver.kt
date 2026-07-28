package com.operit.overflow

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.io.File

class ScreenshotObserver(private val webView: WebView?) {

    private val observers = mutableListOf<FileObserver>()

    private val SCREENSHOT_PATHS = listOf(
        "/storage/emulated/0/Pictures/Screenshots",
        "/storage/emulated/0/DCIM/Screenshots",
        "/storage/emulated/0/Download/Operit/cleanOnExit", // our screenshots too
    )

    fun start() {
        for (path in SCREENSHOT_PATHS) {
            val dir = File(path)
            if (!dir.exists()) continue

            val observer = object : FileObserver(dir, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && isImageFile(path)) {
                        onScreenshotDetected()
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg")
    }

    private fun onScreenshotDetected() {
        Handler(Looper.getMainLooper()).post {
            webView?.evaluateJavascript(
                "window.petEngine && window.petEngine.onScreenshot()", null
            )
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }
}