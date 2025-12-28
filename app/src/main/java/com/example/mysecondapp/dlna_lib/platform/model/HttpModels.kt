package com.example.mysecondapp.dlna_lib.platform.model

import java.io.InputStream

/**
 * Abstraction of an HTTP Request to decouple Core from specific HTTP implementations.
 * Spec Reference: 6.4
 */
data class HttpRequest(
    val method: String,
    val path: String, // relative to server root, e.g., "/desc.xml"
    val headers: Map<String, String>,
    val body: InputStream?
)

/**
 * Abstraction of an HTTP Response.
 * Spec Reference: 6.4
 */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: InputStream? = null
)