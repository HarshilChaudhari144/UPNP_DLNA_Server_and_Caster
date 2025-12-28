package com.example.mysecondapp.dlna_lib.platform

import com.example.mysecondapp.dlna_lib.platform.model.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.model.HttpResponse

/**
 * Functional interface for handling incoming HTTP requests.
 * Used by the Core to register routes on the Platform HTTP server.
 * Spec Reference: 6.4
 */
interface HttpHandler {
    suspend fun handle(request: HttpRequest): HttpResponse
}