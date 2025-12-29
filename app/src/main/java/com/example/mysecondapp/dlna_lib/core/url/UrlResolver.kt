package com.example.mysecondapp.dlna_lib.core.url

import java.net.URI

internal object UrlResolver {

    /**
     * Resolves a path against a base URL.
     *
     * @param baseUrl The absolute base URL (e.g., "http://192.168.1.5:8080/").
     * @param path The path to resolve, which may be absolute or relative.
     * @return A fully qualified absolute URL string.
     */
    fun resolve(baseUrl: String, path: String): String {
        return try {
            val baseUri = URI.create(baseUrl)
            val pathUri = URI.create(path)
            baseUri.resolve(pathUri).toString()
        } catch (e: Exception) {
            // Fallback: simple string concatenation if URI parsing fails
            if (path.startsWith("http")) path else "$baseUrl$path"
        }
    }
}