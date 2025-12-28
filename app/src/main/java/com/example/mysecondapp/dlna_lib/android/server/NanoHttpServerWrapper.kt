package com.example.mysecondapp.dlna_lib.android.server

import android.util.Log
import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpServer
import com.example.mysecondapp.dlna_lib.platform.model.HttpRequest
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.net.InetAddress

/**
 * NanoHTTPD implementation of the Platform HttpServer interface.
 * Updated for Phase 10: Adds forced headers for DLNA compliance (Accept-Ranges, CORS).
 */
class NanoHttpServerWrapper(
    private val ipAddress: InetAddress,
    private val port: Int = 0 // 0 = auto-assign
) : HttpServer {

    private var nanoServer: NanoImpl? = null
    private val handlers = mutableMapOf<String, HttpHandler>()

    override fun start() {
        if (nanoServer?.isAlive == true) return

        // Use port 8080 by default if 0 is passed, otherwise use specific port
        nanoServer = NanoImpl(ipAddress.hostAddress, if (port == 0) 8080 else port).apply {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
//            start(30_000, false)
        }
        Log.d("NanoHttpServer", "Server started on ${getBaseUrl()}")
    }

    override fun stop() {
        nanoServer?.stop()
        nanoServer = null
    }

    override fun registerHandler(path: String, handler: HttpHandler) {
        handlers[path] = handler
    }

    fun getListeningPort(): Int {
        return nanoServer?.listeningPort ?: 0
    }

    fun getBaseUrl(): String {
        val listeningPort = getListeningPort()
        return "http://${ipAddress.hostAddress}:$listeningPort"
    }

    private inner class NanoImpl(hostname: String?, port: Int) : NanoHTTPD(hostname, port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method.name

            // Log non-content requests
            if (!uri.contains("/content/")) {
                Log.d("NanoHttpServer", "Request: $method $uri")
            }

            val handlerEntry = handlers.entries.find { uri.startsWith(it.key) }

            return if (handlerEntry != null) {
                try {
//                    val request = HttpRequest(
//                        method = method,
//                        path = uri,
//                        headers = session.headers,
//                        body = session.inputStream
//                    )
                    val methodName = session.method.name

                    val bodyStream =
                        if (methodName == "POST" || methodName == "NOTIFY") {
                            val map = HashMap<String, String>()
                            session.parseBody(map)
                            map["postData"]?.byteInputStream()
                        } else {
                            null
                        }

                    val request = HttpRequest(
                        method = method,
                        path = uri,
                        headers = session.headers,
                        body = bodyStream
                    )

                    // Execute Logic
                    val response = runBlocking { handlerEntry.value.handle(request) }

                    // 1. Prepare MIME and Length
                    val mimeType = response.headers["Content-Type"] ?: "application/octet-stream"
                    val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L

                    // 2. Create Response
                    // Note: If Status is 206, NanoHTTPD expects us to handle the slicing.
                    // (We assume the Handler provided a sliced stream).
                    val nanoResponse = if (contentLength >= 0) {
                        newFixedLengthResponse(
                            Response.Status.lookup(response.statusCode),
                            mimeType,
                            response.body,
                            contentLength
                        )
                    } else {
                        newChunkedResponse(
                            Response.Status.lookup(response.statusCode),
                            mimeType,
                            response.body
                        )
                    }

                    // 3. Copy Headers from Logic
                    response.headers.forEach { (k, v) ->
                        if (!k.equals("Content-Length", true) && !k.equals("Content-Type", true)) {
                            nanoResponse.addHeader(k, v)
                        }
                    }

                    // 4. FORCE CRITICAL DLNA HEADERS (The Fix)
                    // TVs require these to know they can seek.
                    if (nanoResponse.getHeader("Accept-Ranges") == null) {
                        nanoResponse.addHeader("Accept-Ranges", "bytes")
                    }
                    if (nanoResponse.getHeader("Access-Control-Allow-Origin") == null) {
                        nanoResponse.addHeader("Access-Control-Allow-Origin", "*")
                    }
                    // Prevent caching issues
                    if (nanoResponse.getHeader("Cache-Control") == null) {
                        nanoResponse.addHeader("Cache-Control", "no-cache")
                    }

                    nanoResponse

                } catch (e: Exception) {
                    Log.e("NanoHttpServer", "Error serving request", e)
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error: ${e.message}")
                }
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }
    }
}