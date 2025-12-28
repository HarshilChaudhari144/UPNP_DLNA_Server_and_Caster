package com.example.mysecondapp.dlna_lib.platform

/**
 * A lightweight HTTP server wrapper.
 * The Core uses this to serve Device Descriptions and Media.
 * Spec Reference: 6.4
 */
interface HttpServer {
    fun start()
    fun stop()
    
    /**
     * Registers a handler for a specific URL path.
     * @param path The relative path (e.g., "/dev/desc.xml")
     * @param handler The logic to execute when this path is hit.
     */
    fun registerHandler(path: String, handler: HttpHandler)
}