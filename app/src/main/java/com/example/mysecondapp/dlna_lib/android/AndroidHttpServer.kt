package com.example.mysecondapp.dlna_lib.android

import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpMethod
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse
import com.example.mysecondapp.dlna_lib.platform.HttpServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

class AndroidHttpServer : HttpServer {

    private var nanoServer: WrappedNano? = null

    override fun start(port: Int, handler: HttpHandler) {
        if (nanoServer?.isAlive == true) return

        // 0 means random port in ServerSocket, generally NanoHTTPD supports 0.
        // If NanoHTTPD throws on 0, we might need to find a free port manually,
        // but let's try 0 first (works in most versions).
        nanoServer = WrappedNano(port, handler)
        nanoServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    override fun stop() {
        nanoServer?.stop()
        nanoServer = null
    }

    override fun getPort(): Int {
        return nanoServer?.listeningPort ?: 0
    }

    // Inner Wrapper class
    private inner class WrappedNano(port: Int, private val handler: HttpHandler) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val method = mapMethod(session.method)
            val headers = session.headers ?: emptyMap()
            val uri = session.uri

            // Read Body if POST/NOTIFY
            // NanoHTTPD is tricky; to read body we often must call parseBody or read InputStream manually.
            // For simple SOAP/XML, we can read the stream based on content-length.
            val body = readBody(session)

            val request = HttpRequest(
                method = method,
                path = uri,
                headers = headers,
                body = body,
                inputStream = session.inputStream // Pass raw stream just in case
            )

            // The Core handler is 'suspend', so we bridge it with runBlocking.
            // NanoHTTPD runs this method in a worker thread, so blocking is fine.
            val response: HttpResponse = runBlocking {
                handler.handle(request)
            }

            // Map back to Nano Response
            val status = Response.Status.lookup(response.statusCode) ?: Response.Status.OK
            val mime = response.mimeType ?: "application/octet-stream"

            val nanoResponse = if (response.inputStream != null) {
                // Streaming Response (Video/Thumbnail)
                newChunkedResponse(status, mime, response.inputStream)
            } else {
                // String Response (SOAP/XML)
                val responseBody = response.body ?: ""
                newFixedLengthResponse(status, mime, responseBody)
            }

            // Add Headers
            response.headers.forEach { (k, v) ->
                nanoResponse.addHeader(k, v)
            }

            return nanoResponse
        }

        private fun mapMethod(nanoMethod: Method?): HttpMethod {
            return when (nanoMethod) {
                Method.GET -> HttpMethod.GET
                Method.POST -> HttpMethod.POST
                Method.HEAD -> HttpMethod.HEAD
                // NanoHTTPD standard enum might not have SUBSCRIBE/NOTIFY/UNSUBSCRIBE
                // We handle custom verbs by checking the raw string if possible,
                // or mapping 'lookup' results if you modified NanoHTTPD.
                // Standard NanoHTTPD treats unknown methods as null or error usually.
                // Assuming standard NanoHTTPD 2.3.1+:
                else -> {
                    // Fallback check if Nano didn't recognize custom verbs
                    try {
                        HttpMethod.valueOf(nanoMethod?.name ?: "GET")
                    } catch (e: Exception) {
                        // If it's a custom verb not in Nano's enum, Nano might define it as CONNECT or similar
                        // or we might need to rely on session methods.
                        // For generic usage, default to GET or log warning.
                        HttpMethod.GET
                    }
                }
            }
        }

        private fun readBody(session: IHTTPSession): String? {
            // Only attempt to read body for methods that have one
            val m = session.method
            if (m != Method.POST && m != Method.PUT) return null

            val map = HashMap<String, String>()
            try {
                // parseBody populates the map with form data OR moves temp files.
                // For raw XML (SOAP), NanoHTTPD puts the body content in the map key "postData"
                // IF it didn't match standard multipart logic.
                session.parseBody(map)
                return map["postData"]
            } catch (e: Exception) {
                return null
            }
        }
    }
}