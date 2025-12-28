package com.example.mysecondapp.dlna_lib.platform

import java.io.InputStream

/**
 * Standard HTTP methods supported by DLNA (UPnP).
 */
enum class HttpMethod {
    GET, POST, HEAD, SUBSCRIBE, UNSUBSCRIBE, NOTIFY
}

/**
 * Platform-agnostic representation of an incoming HTTP request.
 */
data class HttpRequest(
    val method: HttpMethod,
    val path: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val inputStream: InputStream? = null
)

/**
 * Platform-agnostic representation of an HTTP response.
 */
data class HttpResponse(
    val statusCode: Int,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val inputStream: InputStream? = null,
    val contentLength: Long? = null
)