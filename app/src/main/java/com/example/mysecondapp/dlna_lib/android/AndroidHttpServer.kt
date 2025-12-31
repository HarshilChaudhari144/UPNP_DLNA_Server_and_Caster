package com.example.mysecondapp.dlna_lib.android

import com.example.mysecondapp.dlna_lib.platform.HttpHandler
import com.example.mysecondapp.dlna_lib.platform.HttpMethod
import com.example.mysecondapp.dlna_lib.platform.HttpRequest
import com.example.mysecondapp.dlna_lib.platform.HttpResponse
import com.example.mysecondapp.dlna_lib.platform.HttpServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream

class AndroidHttpServer : HttpServer {

    private var nanoServer: WrappedNano? = null

    override fun start(port: Int, handler: HttpHandler) {
        if (nanoServer?.isAlive == true) return
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

    private inner class WrappedNano(port: Int, private val handler: HttpHandler) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val method = mapMethod(session.method)
            // Optimization: Only read body for POST
            val body = if (method == HttpMethod.POST) readBody(session) else null

            val request = HttpRequest(
                method = method,
                path = session.uri,
                headers = session.headers ?: emptyMap(),
                body = body,
                inputStream = session.inputStream
            )

            val response: HttpResponse = runBlocking { handler.handle(request) }

            val status = Response.Status.lookup(response.statusCode) ?: Response.Status.OK
            val mime = response.mimeType ?: "application/octet-stream"

            // Safe length check
            val totalLength = response.contentLength
                ?: response.headers["Content-Length"]?.toLongOrNull()
                ?: -1L

            // 1. Create Response
            val nanoResponse = if (response.inputStream != null) {
                // FIX: Use BufferedInputStream (64KB) matching Reference File logic.
                // This prevents ConnectionReset by feeding the socket efficiently.
                val bufferedStream = BufferedInputStream(response.inputStream, 64 * 1024)
                newFixedLengthResponse(status, mime, bufferedStream, totalLength)
            } else {
                newFixedLengthResponse(status, mime, response.body ?: "")
            }

            // 2. Add Headers
            response.headers.forEach { (k, v) ->
                // NanoHTTPD manages Content-Length internally if totalLength is passed above.
                // We add all other headers (DLNA flags, Ranges, etc).
                if (!k.equals("Content-Length", true)) {
                    nanoResponse.addHeader(k, v)
                }
            }

            // FIX: Removed manual HEAD handling.
            // The reference code does NOT manually set data=null.
            // NanoHTTPD's internal 'send' method checks the method and skips writing the body automatically.

            return nanoResponse
        }

        private fun mapMethod(nanoMethod: Method?): HttpMethod {
            return try {
                HttpMethod.valueOf(nanoMethod?.name ?: "GET")
            } catch (e: Exception) { HttpMethod.GET }
        }

        private fun readBody(session: IHTTPSession): String? {
            if (session.method != Method.POST && session.method != Method.PUT) return null
            val map = HashMap<String, String>()
            try { session.parseBody(map); return map["postData"] } catch (e: Exception) { return null }
        }
    }
}