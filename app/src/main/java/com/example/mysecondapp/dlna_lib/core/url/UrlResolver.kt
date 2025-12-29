package com.example.mysecondapp.dlna_lib.core.url

import java.net.URL

internal object UrlResolver {
    /**
     * Combines a base URL with a relative path.
     * Handles cases where the relative path is already an absolute URL.
     */
    fun resolve(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            if (relativeOrAbsolute.startsWith("http")) {
                relativeOrAbsolute
            } else {
                val base = URL(baseUrl)
                val relative = if (relativeOrAbsolute.startsWith("/")) relativeOrAbsolute else "/$relativeOrAbsolute"
                "${base.protocol}://${base.host}:${base.port}$relative"
            }
        } catch (e: Exception) {
            relativeOrAbsolute
        }
    }
}