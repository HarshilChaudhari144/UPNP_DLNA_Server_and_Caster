package com.example.mysecondapp.dlna_lib.platform

/**
 * Logic for processing a request and returning a response.
 */
interface HttpHandler {
    suspend fun handle(request: HttpRequest): HttpResponse
}

/**
 * Interface for a basic TCP server.
 */
interface HttpServer {
    /**
     * Starts the server on the specified port.
     */
    fun start(port: Int, handler: HttpHandler)

    fun stop()

    /**
     * Returns the port the server is currently bound to.
     */
    fun getPort(): Int
}