package com.example.mysecondapp.dlna_lib.android

import android.util.Log
import com.example.mysecondapp.dlna_lib.platform.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream

class AndroidHttpServer : HttpServer {

    private val TAG = "AndroidHttpServer"
    private var nanoServer: WrappedNano? = null

    override fun start(port: Int, handler: HttpHandler) {
        if (nanoServer?.isAlive == true) return
        Log.d(TAG, "Starting HTTP Server on port $port")
        nanoServer = WrappedNano(port, handler)
        nanoServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    override fun stop() {
        Log.d(TAG, "Stopping HTTP Server")
        nanoServer?.stop()
        nanoServer = null
    }

    override fun getPort(): Int = nanoServer?.listeningPort ?: 0

    private inner class WrappedNano(port: Int, private val handler: HttpHandler) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            Log.d(TAG, "HTTP REQ: ${session.method} ${session.uri}") // <--- LOG THIS

            val method = mapMethod(session.method)
            val body = if(method == HttpMethod.POST) readBody(session) else null

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
            val totalLength = response.contentLength ?: -1L

            val nanoResponse = if (response.inputStream != null) {
                val bufferedStream = BufferedInputStream(response.inputStream, 64 * 1024)
                newFixedLengthResponse(status, mime, bufferedStream, totalLength)
            } else {
                newFixedLengthResponse(status, mime, response.body ?: "")
            }

            response.headers.forEach { (k, v) ->
                if (!k.equals("Content-Length", true)) nanoResponse.addHeader(k, v)
            }
//            Never Uncomment the below if. Handling this will stop us from serving media properly.
//            if (method == HttpMethod.HEAD) {
//                nanoResponse.data = null
//                nanoResponse.requestMethod = Method.HEAD
//            }

            return nanoResponse
        }

        private fun mapMethod(nanoMethod: Method?): HttpMethod {
            return try { HttpMethod.valueOf(nanoMethod?.name ?: "GET") } catch (e: Exception) { HttpMethod.GET }
        }

        private fun readBody(session: IHTTPSession): String? {
            if (session.method != Method.POST && session.method != Method.PUT) return null
            val map = HashMap<String, String>()
            try { session.parseBody(map); return map["postData"] } catch (e: Exception) { return null }
        }
    }
}