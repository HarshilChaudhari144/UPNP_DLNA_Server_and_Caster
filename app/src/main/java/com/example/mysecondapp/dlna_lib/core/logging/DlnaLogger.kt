package com.example.mysecondapp.dlna_lib.core.logging

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Internal logger abstraction.
 * Currently delegates to java.util.logging for pure Kotlin compatibility,
 * but can be easily swapped for Android Log or Timber in the platform layer if desired later.
 */
internal object DlnaLogger {
    private const val TAG_PREFIX = "DLNA::"
    private var enabled = true

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
    }

    fun d(tag: String, msg: String) {
        if (!enabled) return
        // In a real Android app, you might swap this for android.util.Log.d
        println("$TAG_PREFIX$tag [DEBUG]: $msg")
    }

    fun w(tag: String, msg: String) {
        if (!enabled) return
        println("$TAG_PREFIX$tag [WARN]: $msg")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (!enabled) return
        System.err.println("$TAG_PREFIX$tag [ERROR]: $msg")
        t?.printStackTrace()
    }
}